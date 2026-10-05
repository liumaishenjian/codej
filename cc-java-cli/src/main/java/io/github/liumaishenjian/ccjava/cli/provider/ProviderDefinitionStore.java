package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileSecurity;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 权限受限的用户级 {@code providers.v1.json} 非秘密 definition store。
 *
 * <p>store 使用 generation CAS、严格 UTF-8/JSON、固定 ceiling 和同目录原子替换。代码注册的
 * Anthropic/OpenRouter definition 不能出现在用户数组中，因而不能被覆盖。默认选择只引用合并
 * catalog 中已有的精确模型；任何损坏或平台安全证明不足均 fail closed。</p>
 */
public final class ProviderDefinitionStore {
    /** providers 文件硬上限。 */
    public static final int MAXIMUM_BYTES = 256 * 1024;
    private static final int MAXIMUM_PROVIDERS = 32;
    private static final long LOCK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "generation", "defaultSelection", "providers", "modelOverrides");
    private static final Set<String> DEFINITION_FIELDS = Set.of(
            "providerId", "kind", "displayName", "baseUri", "apiVariant", "models",
            "defaultModelId", "staticHeaders", "connectTimeoutSeconds", "requestTimeoutSeconds");
    private static final Set<String> SELECTION_FIELDS = Set.of("providerId", "modelId");
    private static final Set<String> PI_SELECTION_FIELDS = Set.of(
            "providerId", "modelId", "backend", "authMethod", "profileId");
    private static final Set<String> MODEL_OVERRIDE_FIELDS = Set.of("providerId", "addedModels", "removedModels");

    private final RestrictedFileSecurity security;
    private final Path file;
    private final Path lockFile;
    private final SecureRandom random;
    private final RestrictedFileSecurity.AtomicMover mover;
    private final ReentrantLock processLock = new ReentrantLock(true);

    /**
     * 从 Composition Root 已解析的 user home 创建生产 store。
     *
     * @param userHome 当前用户主目录
     */
    public ProviderDefinitionStore(Path userHome) {
        this(new RestrictedFileSecurity(userHome), new SecureRandom(), RestrictedFileSecurity.AtomicMover.system());
    }

    ProviderDefinitionStore(RestrictedFileSecurity security, SecureRandom random,
                            RestrictedFileSecurity.AtomicMover mover) {
        this.security = Objects.requireNonNull(security, "security 不能为空");
        this.file = security.root().resolve("providers.v1.json");
        this.lockFile = security.root().resolve(".providers.lock");
        this.random = Objects.requireNonNull(random, "random 不能为空");
        this.mover = Objects.requireNonNull(mover, "mover 不能为空");
    }

    /**
     * 只读当前快照；不存在时返回 generation 0 的空用户 catalog。
     *
     * @param cancellation 等待文件锁期间使用的取消令牌
     * @return 当前非秘密 Provider 配置快照
     */
    public Snapshot snapshot(CancellationToken cancellation) {
        return locked(cancellation, this::read);
    }

    /**
     * 以 generation CAS 新增 custom compatible definition。
     *
     * @param definition 待新增的 OpenAI-compatible Provider 定义
     * @param expectedGeneration 调用方读取到的预期 generation
     * @param cancellation 等待文件锁期间使用的取消令牌
     * @return 发布后的新快照
     */
    public Snapshot add(ProviderDefinition definition, long expectedGeneration,
                        CancellationToken cancellation) {
        Objects.requireNonNull(definition, "definition 不能为空");
        if (definition.kind() != ProviderDefinition.Kind.OPENAI_COMPATIBLE
                || ProviderCatalog.isBuiltinId(definition.providerId())) throw invalid();
        return locked(cancellation, () -> {
            Snapshot old = read();
            requireGeneration(old, expectedGeneration);
            if (old.customDefinitions().size() >= MAXIMUM_PROVIDERS
                    || old.customDefinitions().stream().anyMatch(value ->
                    value.providerId().equals(definition.providerId()))) throw invalid();
            List<ProviderDefinition> values = new ArrayList<>(old.customDefinitions());
            values.add(definition);
            Snapshot next = new Snapshot(old.generation() + 1, values, old.modelOverrides(), old.defaultSelection());
            publish(next);
            return next;
        });
    }

    /**
     * 新增或替换一个 custom compatible definition，并把其默认模型设为全局默认选择。
     *
     * <p>该入口只服务于 CodeJ 的单连接首次配置：相同 Provider ID 会被原位替换，
     * 因而重复执行 {@code /connect} 不会累积定义；内置 Provider 仍不可覆盖。发布前会用
     * 新模型重建默认选择，避免旧模型引用使完整快照失效。</p>
     *
     * @param definition 待保存的 OpenAI-compatible Provider 定义
     * @param expectedGeneration 调用方读取到的预期 generation
     * @param cancellation 等待文件锁期间使用的取消令牌
     * @return 发布后的新快照
     */
    public Snapshot configure(ProviderDefinition definition, long expectedGeneration,
                              CancellationToken cancellation) {
        Objects.requireNonNull(definition, "definition 不能为空");
        if (definition.kind() != ProviderDefinition.Kind.OPENAI_COMPATIBLE
                || ProviderCatalog.isBuiltinId(definition.providerId())) throw invalid();
        return locked(cancellation, () -> {
            Snapshot old = read();
            requireGeneration(old, expectedGeneration);
            boolean existing = old.customDefinitions().stream()
                    .anyMatch(value -> value.providerId().equals(definition.providerId()));
            if (!existing && old.customDefinitions().size() >= MAXIMUM_PROVIDERS) throw invalid();
            List<ProviderDefinition> values = new ArrayList<>(old.customDefinitions());
            values.removeIf(value -> value.providerId().equals(definition.providerId()));
            values.add(definition);
            Snapshot next = new Snapshot(old.generation() + 1, values, old.modelOverrides(),
                    Optional.of(new DefaultSelection(definition.providerId(), definition.defaultModelId())));
            next.catalog();
            publish(next);
            return next;
        });
    }

    /**
     * 以 generation CAS 删除 custom definition；仍被默认选择引用时拒绝。
     *
     * @param providerId 待删除的 custom Provider 标识
     * @param expectedGeneration 调用方读取到的预期 generation
     * @param cancellation 等待文件锁期间使用的取消令牌
     * @return 发布后的新快照
     */
    public Snapshot remove(String providerId, long expectedGeneration, CancellationToken cancellation) {
        return locked(cancellation, () -> {
            Snapshot old = read();
            requireGeneration(old, expectedGeneration);
            if (ProviderCatalog.isBuiltinId(providerId)) throw invalid();
            boolean found = old.customDefinitions().stream()
                    .anyMatch(value -> value.providerId().equals(providerId));
            if (!found) throw failure(ProviderAuthException.Code.PROVIDER_UNKNOWN, false);
            if (old.defaultSelection().filter(value -> value.backend().equals("spring-ai")
                    && value.providerId().equals(providerId)).isPresent()) {
                throw invalid();
            }
            List<ProviderDefinition> values = old.customDefinitions().stream()
                    .filter(value -> !value.providerId().equals(providerId)).toList();
            Snapshot next = new Snapshot(old.generation() + 1, values, old.modelOverrides(), old.defaultSelection());
            publish(next);
            return next;
        });
    }

    /**
     * 以 generation CAS 给 built-in Provider 增加一个模型 ID。
     *
     * @param providerId built-in Provider 标识
     * @param modelId 待增加的模型标识
     * @param expectedGeneration 调用方读取到的预期 generation
     * @param cancellation 等待文件锁期间使用的取消令牌
     * @return 发布后的新快照
     */
    public Snapshot addModel(String providerId, String modelId, long expectedGeneration,
                             CancellationToken cancellation) {
        return mutateModel(providerId, modelId, true, expectedGeneration, cancellation);
    }

    /**
     * 以 generation CAS 隐藏一个模型 ID；代码默认模型及当前持久默认不可删除。
     *
     * @param providerId built-in Provider 标识
     * @param modelId 待隐藏的模型标识
     * @param expectedGeneration 调用方读取到的预期 generation
     * @param cancellation 等待文件锁期间使用的取消令牌
     * @return 发布后的新快照
     */
    public Snapshot removeModel(String providerId, String modelId, long expectedGeneration,
                                CancellationToken cancellation) {
        return mutateModel(providerId, modelId, false, expectedGeneration, cancellation);
    }

    private Snapshot mutateModel(String providerId, String modelId, boolean add, long expectedGeneration,
                                 CancellationToken cancellation) {
        return locked(cancellation, () -> {
            Snapshot old = read();
            requireGeneration(old, expectedGeneration);
            ProviderDefinition baseline;
            try {
                baseline = ProviderCatalog.builtin(providerId);
            } catch (IllegalArgumentException unknown) {
                throw failure(ProviderAuthException.Code.PROVIDER_UNKNOWN, false);
            }
            ProviderDefinition effective = old.catalog().require(providerId);
            if (add && effective.models().contains(modelId) || !add && !effective.models().contains(modelId)) throw invalid();
            if (!add && (baseline.defaultModelId().equals(modelId)
                    || old.defaultSelection().filter(value -> value.backend().equals("spring-ai")
                    && value.providerId().equals(providerId) && value.modelId().equals(modelId)).isPresent())) throw invalid();
            List<ProviderCatalog.ModelOverride> overrides = new ArrayList<>(old.modelOverrides());
            ProviderCatalog.ModelOverride prior = overrides.stream()
                    .filter(value -> value.providerId().equals(providerId)).findFirst()
                    .orElse(new ProviderCatalog.ModelOverride(providerId, List.of(), List.of()));
            overrides.removeIf(value -> value.providerId().equals(providerId));
            List<String> added = new ArrayList<>(prior.addedModels());
            List<String> removed = new ArrayList<>(prior.removedModels());
            if (add) {
                removed.remove(modelId);
                if (!baseline.models().contains(modelId)) added.add(modelId);
            } else {
                added.remove(modelId);
                if (baseline.models().contains(modelId)) removed.add(modelId);
            }
            try {
                if (!added.isEmpty() || !removed.isEmpty()) {
                    overrides.add(new ProviderCatalog.ModelOverride(providerId, added, removed));
                }
                Snapshot next = new Snapshot(old.generation() + 1, old.customDefinitions(), overrides,
                        old.defaultSelection());
                // 在发布前执行完整合并校验，包括 ceiling、冲突和默认保留。
                next.catalog();
                publish(next);
                return next;
            } catch (IllegalArgumentException invalidModel) {
                throw invalid();
            }
        });
    }

    /**
     * 以 generation CAS 设置或清除下一 Run 的用户默认 provider/model。
     *
     * @param selection 新默认选择；为空 Optional 时清除持久默认
     * @param expectedGeneration 调用方读取到的预期 generation
     * @param cancellation 等待文件锁期间使用的取消令牌
     * @return 发布后的新快照
     */
    public Snapshot selectDefault(Optional<DefaultSelection> selection, long expectedGeneration,
                                  CancellationToken cancellation) {
        Objects.requireNonNull(selection, "selection 不能为空");
        return locked(cancellation, () -> {
            Snapshot old = read();
            requireGeneration(old, expectedGeneration);
            selection.ifPresent(value -> requireSelection(old.catalog(), value));
            Snapshot next = new Snapshot(old.generation() + 1, old.customDefinitions(), old.modelOverrides(), selection);
            publish(next);
            return next;
        });
    }

    private Snapshot read() {
        ensureLayout();
        if (!security.exists(file)) return new Snapshot(0, List.of(), Optional.empty());
        byte[] bytes;
        try {
            bytes = security.read(file, MAXIMUM_BYTES);
        } catch (SecurityException insecure) {
            throw failure(ProviderAuthException.Code.AUTH_STORE_INSECURE, false);
        }
        try {
            return parse(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private Snapshot parse(byte[] bytes) {
        JsonNode root = parseTree(bytes);
        requireOnlyFields(root, ROOT_FIELDS);
        requireInteger(root, "schemaVersion", 1, 1);
        long generation = root.has("generation") ? requireLong(root, "generation", 0, Long.MAX_VALUE) : 0;
        JsonNode providers = requireArray(root, "providers");
        if (providers.size() > MAXIMUM_PROVIDERS) throw corrupt();
        List<ProviderDefinition> definitions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : providers) {
            ProviderDefinition definition = parseDefinition(node);
            if (definition.kind() != ProviderDefinition.Kind.OPENAI_COMPATIBLE
                    || ProviderCatalog.isBuiltinId(definition.providerId())
                    || !ids.add(definition.providerId())) throw corrupt();
            definitions.add(definition);
        }
        List<ProviderCatalog.ModelOverride> overrides = new ArrayList<>();
        JsonNode modelOverrides = root.get("modelOverrides");
        if (modelOverrides != null) {
            if (!modelOverrides.isArray() || modelOverrides.size() > 2) throw corrupt();
            Set<String> overrideIds = new HashSet<>();
            for (JsonNode node : modelOverrides) {
                requireExactFields(node, MODEL_OVERRIDE_FIELDS);
                ProviderCatalog.ModelOverride override;
                try {
                    override = new ProviderCatalog.ModelOverride(text(node, "providerId"),
                            parseModels(node, "addedModels"), parseModels(node, "removedModels"));
                } catch (RuntimeException invalid) {
                    throw corrupt();
                }
                if (!overrideIds.add(override.providerId())) throw corrupt();
                overrides.add(override);
            }
        }
        Snapshot partial;
        try {
            partial = new Snapshot(generation, definitions, overrides, Optional.empty());
            partial.catalog();
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
        Optional<DefaultSelection> selection = Optional.empty();
        JsonNode selected = root.get("defaultSelection");
        if (selected != null && !selected.isNull()) {
            try {
                if (selected.has("backend")) {
                    requireExactFields(selected, PI_SELECTION_FIELDS);
                    if (!"pi".equals(text(selected, "backend"))) throw corrupt();
                    selection = Optional.of(new DefaultSelection(text(selected, "providerId"),
                            text(selected, "modelId"), text(selected, "backend"), text(selected, "authMethod"),
                            Optional.of(text(selected, "profileId"))));
                } else {
                    requireExactFields(selected, SELECTION_FIELDS);
                    selection = Optional.of(new DefaultSelection(text(selected, "providerId"), text(selected, "modelId")));
                }
                requireSelection(partial.catalog(), selection.orElseThrow());
            } catch (RuntimeException invalid) {
                throw corrupt();
            }
        }
        return new Snapshot(generation, definitions, overrides, selection);
    }

    private static List<String> parseModels(JsonNode node, String field) {
        JsonNode values = requireArray(node, field);
        if (values.size() > 128) throw corrupt();
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(requiredText(value)));
        return result;
    }

    private ProviderDefinition parseDefinition(JsonNode node) {
        requireExactFields(node, DEFINITION_FIELDS);
        try {
            JsonNode modelsNode = requireArray(node, "models");
            List<String> models = new ArrayList<>();
            modelsNode.forEach(value -> models.add(requiredText(value)));
            JsonNode headersNode = requireObject(node, "staticHeaders");
            Map<String, String> headers = new LinkedHashMap<>();
            headersNode.properties().forEach(entry -> {
                if (headers.put(entry.getKey(), requiredText(entry.getValue())) != null) throw corrupt();
            });
            return new ProviderDefinition(
                    text(node, "providerId"), ProviderDefinition.Kind.valueOf(text(node, "kind")),
                    text(node, "displayName"), URI.create(text(node, "baseUri")),
                    ProviderDefinition.ApiVariant.valueOf(text(node, "apiVariant")), models,
                    text(node, "defaultModelId"), headers,
                    Duration.ofSeconds(requireLong(node, "connectTimeoutSeconds", 1, 30)),
                    Duration.ofSeconds(requireLong(node, "requestTimeoutSeconds", 1, 300)));
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
    }

    private void publish(Snapshot snapshot) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("generation", snapshot.generation());
        snapshot.defaultSelection().ifPresent(value -> root.put("defaultSelection",
                value.backend().equals("pi")
                        ? Map.of("providerId", value.providerId(), "modelId", value.modelId(),
                                "backend", value.backend(), "authMethod", value.authMethod(),
                                "profileId", value.profileId().orElseThrow())
                        : Map.of("providerId", value.providerId(), "modelId", value.modelId())));
        List<Object> providers = new ArrayList<>();
        snapshot.customDefinitions().stream().sorted(Comparator.comparing(ProviderDefinition::providerId))
                .forEach(value -> providers.add(serialize(value)));
        root.put("providers", providers);
        List<Object> modelOverrides = new ArrayList<>();
        snapshot.modelOverrides().stream().sorted(Comparator.comparing(ProviderCatalog.ModelOverride::providerId))
                .forEach(value -> modelOverrides.add(serialize(value)));
        if (!modelOverrides.isEmpty()) root.put("modelOverrides", modelOverrides);
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(root);
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
        try {
            security.atomicWrite(file, file.resolveSibling(".tmp-" + randomId()), bytes, MAXIMUM_BYTES,
                    this::parse, mover);
        } catch (SecurityException insecure) {
            throw failure(ProviderAuthException.Code.AUTH_STORE_INSECURE, false);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static Object serialize(ProviderDefinition value) {
        LinkedHashMap<String, Object> node = new LinkedHashMap<>();
        node.put("providerId", value.providerId());
        node.put("kind", value.kind().name());
        node.put("displayName", value.displayName());
        node.put("baseUri", value.baseUri().toString());
        node.put("apiVariant", value.apiVariant().name());
        node.put("models", value.models());
        node.put("defaultModelId", value.defaultModelId());
        node.put("staticHeaders", new java.util.TreeMap<>(value.staticHeaders()));
        node.put("connectTimeoutSeconds", value.connectTimeout().toSeconds());
        node.put("requestTimeoutSeconds", value.requestTimeout().toSeconds());
        return node;
    }

    private static Object serialize(ProviderCatalog.ModelOverride value) {
        LinkedHashMap<String, Object> node = new LinkedHashMap<>();
        node.put("providerId", value.providerId());
        node.put("addedModels", value.addedModels().stream().sorted().toList());
        node.put("removedModels", value.removedModels().stream().sorted().toList());
        return node;
    }

    private void ensureLayout() {
        try {
            security.ensureDirectory(security.root());
            security.ensureFile(lockFile);
        } catch (SecurityException insecure) {
            throw failure(ProviderAuthException.Code.AUTH_STORE_INSECURE, false);
        }
    }

    private <T> T locked(CancellationToken cancellation, Operation<T> operation) {
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        long deadline = System.nanoTime() + LOCK_TIMEOUT_NANOS;
        boolean acquired = false;
        try {
            while (!(acquired = processLock.tryLock(25, TimeUnit.MILLISECONDS))) requireActive(cancellation, deadline);
            ensureLayout();
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                while (true) {
                    requireActive(cancellation, deadline);
                    try {
                        FileLock lock = channel.tryLock();
                        if (lock != null) try (lock) { return operation.run(); }
                    } catch (java.nio.channels.OverlappingFileLockException active) {
                        // 另一实例持锁；继续响应取消和 deadline。
                    }
                    Thread.sleep(25);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(ProviderAuthException.Code.AUTH_CANCELLED, true);
        } catch (java.io.IOException io) {
            throw failure(ProviderAuthException.Code.AUTH_STORE_LOCKED, true);
        } finally {
            if (acquired) processLock.unlock();
        }
    }

    private static void requireActive(CancellationToken cancellation, long deadline) {
        if (cancellation.isCancellationRequested()) throw failure(ProviderAuthException.Code.AUTH_CANCELLED, true);
        if (System.nanoTime() - deadline >= 0) throw failure(ProviderAuthException.Code.AUTH_STORE_LOCKED, true);
    }

    private static void requireGeneration(Snapshot snapshot, long expected) {
        if (snapshot.generation() != expected) {
            throw failure(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT, true);
        }
    }

    private static void requireSelection(ProviderCatalog catalog, DefaultSelection selection) {
        // Pi canonical 已用包内静态目录验证，不查询同名 legacy definition。
        if (selection.backend().equals("pi")) return;
        ProviderDefinition definition;
        try {
            definition = catalog.require(selection.providerId());
        } catch (IllegalArgumentException unknown) {
            throw corrupt();
        }
        if (!definition.models().contains(selection.modelId())) throw corrupt();
    }

    private String randomId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static JsonNode parseTree(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return JSON.readTree(bytes);
        } catch (CharacterCodingException | RuntimeException invalid) {
            throw corrupt();
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected) {
        requireOnlyFields(node, expected);
        if (!node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                .equals(expected)) throw corrupt();
    }
    private static void requireOnlyFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()
                || node.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) throw corrupt();
        Set<String> required = new HashSet<>(fields);
        required.remove("generation");
        required.remove("defaultSelection");
        required.remove("modelOverrides");
        if (!node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                .containsAll(required)) throw corrupt();
    }
    private static JsonNode requireArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw corrupt();
        return value;
    }
    private static JsonNode requireObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) throw corrupt();
        return value;
    }
    private static String text(JsonNode node, String field) { return requiredText(node.get(field)); }
    private static String requiredText(JsonNode node) {
        if (node == null || !node.isTextual()) throw corrupt();
        return node.asText();
    }
    private static long requireLong(JsonNode node, String field, long minimum, long maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw corrupt();
        long result = value.longValue();
        if (result < minimum || result > maximum) throw corrupt();
        return result;
    }
    private static void requireInteger(JsonNode node, String field, int minimum, int maximum) {
        long value = requireLong(node, field, minimum, maximum);
        if (value != (int) value) throw corrupt();
    }
    private static ProviderAuthException invalid() {
        return failure(ProviderAuthException.Code.PROVIDER_DEFINITION_INVALID, false);
    }
    private static ProviderAuthException corrupt() {
        return failure(ProviderAuthException.Code.AUTH_STORE_CORRUPT, false);
    }
    private static ProviderAuthException failure(ProviderAuthException.Code code, boolean retryable) {
        return new ProviderAuthException(code, ProviderAuthException.Action.CHECK_LOCAL_STORE, retryable);
    }

    /**
     * 非秘密持久化快照。
     *
     * @param generation 用于 CAS 更新的单调递增版本
     * @param customDefinitions 用户定义的 OpenAI-compatible Provider 列表
     * @param modelOverrides built-in Provider 的模型增删 overlay
     * @param defaultSelection 下一 Run 使用的可选持久默认选择
     */
    public record Snapshot(long generation, List<ProviderDefinition> customDefinitions,
                           List<ProviderCatalog.ModelOverride> modelOverrides,
                           Optional<DefaultSelection> defaultSelection) {
        /**
         * 创建没有 built-in 模型 overlay 的兼容快照。
         *
         * @param generation 用于 CAS 更新的单调递增版本
         * @param customDefinitions 用户定义的 OpenAI-compatible Provider 列表
         * @param defaultSelection 下一 Run 使用的可选持久默认选择
         */
        public Snapshot(long generation, List<ProviderDefinition> customDefinitions,
                        Optional<DefaultSelection> defaultSelection) {
            this(generation, customDefinitions, List.of(), defaultSelection);
        }

        /** 校验数量、重复 identity、overlay 与默认引用。 */
        public Snapshot {
            if (generation < 0) throw invalid();
            customDefinitions = List.copyOf(Objects.requireNonNull(customDefinitions));
            modelOverrides = List.copyOf(Objects.requireNonNull(modelOverrides));
            defaultSelection = Objects.requireNonNull(defaultSelection);
            if (customDefinitions.size() > MAXIMUM_PROVIDERS || modelOverrides.size() > 2) throw invalid();
            Set<String> ids = new HashSet<>();
            for (ProviderDefinition value : customDefinitions) {
                if (value.kind() != ProviderDefinition.Kind.OPENAI_COMPATIBLE
                        || ProviderCatalog.isBuiltinId(value.providerId()) || !ids.add(value.providerId())) throw invalid();
            }
            Set<String> overrideIds = new HashSet<>();
            if (modelOverrides.stream().anyMatch(value -> !overrideIds.add(value.providerId()))) throw invalid();
        }
        /**
         * 返回 built-in overlay 与 custom 合并后的稳定 catalog。
         *
         * @return 合并并校验后的 Provider catalog
         */
        public ProviderCatalog catalog() { return new ProviderCatalog(customDefinitions, modelOverrides); }
    }

    /**
     * 非秘密默认选择；legacy 延续 Provider 默认 profile，Pi 显式绑定完整身份。
     *
     * @param providerId 默认 Provider 的稳定标识
     * @param modelId 默认模型的精确标识
     * @param backend spring-ai 或 pi，不根据同名 Provider 推断
     * @param authMethod API_KEY 或 OAUTH
     * @param profileId legacy 必须为空；Pi 必须为明确的 profile 名，不含凭据版本
     */
    public record DefaultSelection(String providerId, String modelId, String backend,
                                   String authMethod, Optional<String> profileId) {
        /**
         * 保留旧两字段默认及原磁盘格式，不自动迁移。
         * @param providerId 旧 Provider 标识
         * @param modelId 旧模型标识
         */
        public DefaultSelection(String providerId, String modelId) {
            this(providerId, modelId, "spring-ai", "API_KEY", Optional.empty());
        }

        /** 复用完整身份契约与包内目录；错误不携带私有输入或底层 cause。 */
        public DefaultSelection {
            try {
                Objects.requireNonNull(profileId);
                new ProviderSelectionSnapshot(providerId, profileId.orElse("selection"), modelId, backend, authMethod);
                if ("spring-ai".equals(backend)) {
                    if (profileId.isPresent()) throw invalid();
                } else {
                    PiCredentialIdentity identity = new PiCredentialIdentity(backend, providerId,
                            PiCredentialIdentity.AuthMethod.valueOf(authMethod), profileId.orElseThrow());
                    PiProviderCatalog catalog = new PiProviderCatalog();
                    String method = identity.authMethod() == PiCredentialIdentity.AuthMethod.API_KEY ? "api_key" : "oauth";
                    if (catalog.require(providerId).authMethods().stream().noneMatch(value -> value.id().equals(method))) {
                        throw invalid();
                    }
                    catalog.requireModel(providerId, modelId);
                }
            } catch (RuntimeException invalidIdentity) {
                throw invalid();
            }
        }
    }

    @FunctionalInterface
    private interface Operation<T> { T run(); }
}
