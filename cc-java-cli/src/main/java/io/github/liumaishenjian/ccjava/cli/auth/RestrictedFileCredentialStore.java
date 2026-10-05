package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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

import static io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException.Action.CHECK_LOCAL_STORE;
import static io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException.Code.*;

/**
 * 权限受限的用户级 credential 文件 store。
 *
 * <p>所有 mutation 在进程锁、可取消的进程间锁与 phase journal 内执行。恢复只以 index/generation
 * 为事实源：未发布的新 secret 被清理，已发布的新引用被保留，旧引用在发布后清理；引用缺失绝不
 * 回退。路径、Unix owner/mode、Windows DACL、reparse、hard-link 与 identity 由
 * {@link RestrictedFileSecurity} 统一证明。本实现不宣称 OS vault。</p>
 */
public final class RestrictedFileCredentialStore implements CredentialStore {
    static final int MAX_INDEX_BYTES = 256 * 1024;
    static final int MAX_SECRET_BYTES = 20 * 1024;
    static final int MAX_TXN_BYTES = 8 * 1024;
    private static final long LOCK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long LOCK_POLL_MILLIS = 25;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> INDEX_FIELDS = Set.of(
            "schemaVersion", "generation", "providerDefaults", "profiles");
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "id", "providerId", "authMethod", "secretRef", "createdAt", "updatedAt", "lastProbe");
    private static final Set<String> REF_FIELDS = Set.of("kind", "secretId", "variableName");
    private static final Set<String> SECRET_FIELDS = Set.of("schemaVersion", "secretId", "kind", "value");
    private static final Set<String> TXN_FIELDS = Set.of(
            "schemaVersion", "operation", "providerId", "profileId", "oldSecretId", "newSecretId",
            "expectedGeneration", "phase");

    private final RestrictedFileSecurity security;
    private final Path authRoot;
    private final Path profiles;
    private final Path secrets;
    private final Path lockFile;
    private final Path transaction;
    private final Clock clock;
    private final SecureRandom random;
    private final RestrictedFileSecurity.AtomicMover mover;
    private final FaultInjector faults;
    private final ReentrantLock processLock = new ReentrantLock(true);

    /**
     * 使用已解析的 user home 创建生产 store。
     *
     * @param userHome 已解析的用户主目录
     */
    public RestrictedFileCredentialStore(Path userHome) {
        this(userHome, Clock.systemUTC(), new SecureRandom());
    }

    /**
     * 使用 fake clock/random 创建确定性 store。
     *
     * @param userHome 已解析的用户主目录
     * @param clock 提供 credential 时间戳的时钟
     * @param random 生成 secret 标识的安全随机数源
     */
    public RestrictedFileCredentialStore(Path userHome, Clock clock, SecureRandom random) {
        this(new RestrictedFileSecurity(userHome), clock, random,
                RestrictedFileSecurity.AtomicMover.system(), FaultInjector.none());
    }

    RestrictedFileCredentialStore(RestrictedFileSecurity security, Clock clock, SecureRandom random,
                                  RestrictedFileSecurity.AtomicMover mover, FaultInjector faults) {
        this.security = Objects.requireNonNull(security, "security 不能为空");
        this.authRoot = security.root().resolve("auth");
        this.profiles = authRoot.resolve("profiles.v1.json");
        this.secrets = authRoot.resolve("secrets");
        this.lockFile = authRoot.resolve(".lock");
        this.transaction = authRoot.resolve(".txn.v1.json");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.random = Objects.requireNonNull(random, "random 不能为空");
        this.mover = Objects.requireNonNull(mover, "mover 不能为空");
        this.faults = Objects.requireNonNull(faults, "faults 不能为空");
    }

    @Override
    public Snapshot snapshot(CancellationToken cancellation) {
        return locked(cancellation, () -> {
            recover();
            return readIndex();
        });
    }

    @Override
    public CredentialProfile saveStore(String provider, String profile, SecretMaterial secret,
                                       boolean setDefault, CancellationToken cancellation) {
        return saveStoreInternal(provider, profile, secret, setDefault, -1, cancellation);
    }

    /** 在同一文件锁内恢复、读取并 CAS；负代次不能从公开 CAS 入口绕过校验。 */
    @Override
    public CredentialProfile saveStore(String provider, String profile, SecretMaterial secret,
                                       boolean setDefault, long expectedGeneration, CancellationToken cancellation) {
        try (secret) {
            if (expectedGeneration < 0) throw failure(AUTH_TRANSACTION_CONFLICT, true);
            return saveStoreInternal(provider, profile, secret, setDefault, expectedGeneration, cancellation);
        }
    }

    private CredentialProfile saveStoreInternal(String provider, String profile, SecretMaterial secret,
                                                boolean setDefault, long expected, CancellationToken cancellation) {
        Objects.requireNonNull(secret, "secret 不能为空");
        try (secret) {
        return locked(cancellation, () -> {
            recover();
            Snapshot old = readIndex();
            requireSaveGeneration(old, expected, cancellation);
            ensureCapacity(old, provider, profile);
            String newId = newSecretId();
            Optional<String> oldId = old.find(provider, profile).flatMap(RestrictedFileCredentialStore::storedId);
            char[] chars = secret.copyChars();
            try {
                requireSaveGeneration(old, expected, cancellation);
                writeSecret(newId, chars);
                faults.after(CrashPoint.NEW_SECRET_DURABLE);
            } finally {
                Arrays.fill(chars, '\0');
            }
            Txn txn = new Txn(Operation.SAVE, provider, profile, oldId, Optional.of(newId),
                    old.generation(), Phase.SECRET_DURABLE);
            writeTxn(txn);
            faults.after(CrashPoint.JOURNAL_SECRET_DURABLE);
            Instant now = clock.instant();
            CredentialProfile previous = old.find(provider, profile).orElse(null);
            CredentialProfile value = new CredentialProfile(profile, provider, new SecretRef.Store(newId),
                    previous == null ? now : previous.createdAt(), now, Optional.empty());
            publish(upsert(old, value, setDefault, old.generation() + 1));
            faults.after(CrashPoint.INDEX_PUBLISHED);
            writeTxn(txn.withPhase(Phase.INDEX_PUBLISHED));
            faults.after(CrashPoint.JOURNAL_INDEX_PUBLISHED);
            oldId.filter(id -> !id.equals(newId)).ifPresent(this::deleteSecret);
            faults.after(CrashPoint.OLD_SECRET_DELETED);
            clearTxn();
            cleanupOrphans(readIndex(), Set.of());
            return value;
        });
        }
    }

    @Override
    public CredentialProfile saveEnv(String provider, String profile, String envName,
                                     boolean setDefault, CancellationToken cancellation) {
        return saveEnvInternal(provider, profile, envName, setDefault, -1, cancellation);
    }

    /** 与 STORE 使用相同锁内 CAS；ENV 不读取或复制环境变量值。 */
    @Override
    public CredentialProfile saveEnv(String provider, String profile, String envName,
                                     boolean setDefault, long expectedGeneration, CancellationToken cancellation) {
        if (expectedGeneration < 0) throw failure(AUTH_TRANSACTION_CONFLICT, true);
        return saveEnvInternal(provider, profile, envName, setDefault, expectedGeneration, cancellation);
    }

    private CredentialProfile saveEnvInternal(String provider, String profile, String envName,
                                              boolean setDefault, long expected, CancellationToken cancellation) {
        return locked(cancellation, () -> {
            recover();
            Snapshot old = readIndex();
            requireSaveGeneration(old, expected, cancellation);
            ensureCapacity(old, provider, profile);
            Optional<String> oldId = old.find(provider, profile).flatMap(RestrictedFileCredentialStore::storedId);
            Txn txn = new Txn(Operation.SAVE, provider, profile, oldId, Optional.empty(),
                    old.generation(), Phase.SECRET_DURABLE);
            requireSaveGeneration(old, expected, cancellation);
            writeTxn(txn);
            faults.after(CrashPoint.JOURNAL_SECRET_DURABLE);
            Instant now = clock.instant();
            CredentialProfile previous = old.find(provider, profile).orElse(null);
            CredentialProfile value = new CredentialProfile(profile, provider, new SecretRef.Env(envName),
                    previous == null ? now : previous.createdAt(), now, Optional.empty());
            publish(upsert(old, value, setDefault, old.generation() + 1));
            faults.after(CrashPoint.INDEX_PUBLISHED);
            writeTxn(txn.withPhase(Phase.INDEX_PUBLISHED));
            faults.after(CrashPoint.JOURNAL_INDEX_PUBLISHED);
            oldId.ifPresent(this::deleteSecret);
            faults.after(CrashPoint.OLD_SECRET_DELETED);
            clearTxn();
            cleanupOrphans(readIndex(), Set.of());
            return value;
        });
    }
    @Override
    public boolean secretExists(SecretRef.Store ref, CancellationToken cancellation) {
        return locked(cancellation, () -> {
            recover();
            return security.exists(secretPath(ref.secretId()));
        });
    }


    @Override
    public SecretMaterial readSecret(SecretRef.Store ref, CancellationToken cancellation) {
        return locked(cancellation, () -> {
            recover();
            Path file = secretPath(ref.secretId());
            if (!security.exists(file)) throw failure(AUTH_SECRET_UNAVAILABLE, false);
            byte[] bytes = security.read(file, MAX_SECRET_BYTES);
            try {
                return parseSecret(bytes, ref.secretId());
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        });
    }

    @Override
    public void delete(String provider, String profile, long expected, CancellationToken cancellation) {
        locked(cancellation, () -> {
            recover();
            Snapshot old = readIndex();
            if (old.generation() != expected) throw failure(AUTH_TRANSACTION_CONFLICT, true);
            if (cancellation.isCancellationRequested()) throw failure(AUTH_CANCELLED, true);
            CredentialProfile target = old.find(provider, profile)
                    .orElseThrow(() -> failure(AUTH_PROFILE_UNKNOWN, false));
            Optional<String> oldId = storedId(target);
            Txn txn = new Txn(Operation.LOGOUT, provider, profile, oldId, Optional.empty(),
                    old.generation(), Phase.SECRET_DURABLE);
            writeTxn(txn);
            faults.after(CrashPoint.JOURNAL_SECRET_DURABLE);
            List<CredentialProfile> next = old.profiles().stream()
                    .filter(value -> !(value.providerId().equals(provider) && value.profileId().equals(profile)))
                    .toList();
            Map<String, String> defaults = new HashMap<>(old.providerDefaults());
            defaults.remove(provider, profile);
            publish(new Snapshot(old.generation() + 1, next, defaults));
            faults.after(CrashPoint.INDEX_PUBLISHED);
            writeTxn(txn.withPhase(Phase.INDEX_PUBLISHED));
            faults.after(CrashPoint.JOURNAL_INDEX_PUBLISHED);
            oldId.ifPresent(this::deleteSecret);
            faults.after(CrashPoint.OLD_SECRET_DELETED);
            clearTxn();
            cleanupOrphans(readIndex(), Set.of());
            return null;
        });
    }

    /** 兼容旧调用方的引用检查；异步探测必须改用绑定开始代次的重载。 */
    @Override
    public CredentialProfile saveProbe(String provider, String profile, CredentialProfile.ProbeRecord probe,
                                       SecretRef expectedSecretRef, CancellationToken cancellation) {
        return saveProbeTransaction(provider, profile, probe, expectedSecretRef, -1, cancellation);
    }

    /** 锁内同时校验登录代次与引用；元数据更新本身不推进凭据代次。 */
    @Override
    public CredentialProfile saveProbe(String provider, String profile, CredentialProfile.ProbeRecord probe,
                                       SecretRef expectedSecretRef, long expectedGeneration, CancellationToken cancellation) {
        if (expectedGeneration < 0) throw new IllegalArgumentException("expectedGeneration不能为负");
        return saveProbeTransaction(provider, profile, probe, expectedSecretRef, expectedGeneration, cancellation);
    }

    private CredentialProfile saveProbeTransaction(String provider, String profile, CredentialProfile.ProbeRecord probe,
                                       SecretRef expectedSecretRef, long expectedGeneration, CancellationToken cancellation) {
        Objects.requireNonNull(probe, "probe 不能为空");
        return locked(cancellation, () -> {
            recover();
            Snapshot old = readIndex();
            requireSaveGeneration(old, expectedGeneration, cancellation);
            CredentialProfile current = old.find(provider, profile)
                    .orElseThrow(() -> failure(AUTH_PROFILE_UNKNOWN, false));
            if (!current.secretRef().equals(expectedSecretRef)) throw failure(AUTH_TRANSACTION_CONFLICT, true);
            CredentialProfile updated = new CredentialProfile(current.profileId(), current.providerId(),
                    current.secretRef(), current.createdAt(), current.updatedAt(), Optional.of(probe));
            List<CredentialProfile> next = old.profiles().stream().map(value -> value.providerId().equals(provider)
                    && value.profileId().equals(profile) ? updated : value).toList();
            // lastProbe 不是 auth material mutation，不推进 lease fencing generation。
            publish(new Snapshot(old.generation(), next, old.providerDefaults()));
            return updated;
        });
    }
    /** -1 只供旧签名无条件写兼容；新应用入口必须使用非负代次。 */
    private static void requireSaveGeneration(Snapshot old, long expected, CancellationToken cancellation) {
        if (cancellation.isCancellationRequested()) throw failure(AUTH_CANCELLED, true);
        if (expected >= 0 && old.generation() != expected) throw failure(AUTH_TRANSACTION_CONFLICT, true);
    }

    private void recover() {
        Snapshot index = readIndexWithoutRecovery();
        Optional<Txn> pending = readTxn();
        if (pending.isPresent()) {
            Txn txn = pending.orElseThrow();
            if (index.generation() == txn.expectedGeneration()) {
                // index 未发布：新 secret 不是事实源，旧引用保持不变。
                txn.newSecretId().ifPresent(this::deleteSecret);
                clearTxn();
            } else if (index.generation() == txn.expectedGeneration() + 1) {
                boolean published = txn.operation() == Operation.LOGOUT
                        ? index.find(txn.providerId(), txn.profileId()).isEmpty()
                        : reflectsSavedIndex(index, txn);
                if (!published) throw failure(AUTH_TRANSACTION_CONFLICT, false);
                txn.oldSecretId().filter(id -> !txn.newSecretId().orElse("").equals(id))
                        .ifPresent(this::deleteSecret);
                clearTxn();
            } else {
                throw failure(AUTH_TRANSACTION_CONFLICT, false);
            }
        }
        cleanupOrphans(index, Set.of());
    }

    private boolean reflectsSavedIndex(Snapshot index, Txn txn) {
        Optional<CredentialProfile> value = index.find(txn.providerId(), txn.profileId());
        if (value.isEmpty()) return false;
        if (txn.newSecretId().isPresent()) {
            return value.orElseThrow().secretRef() instanceof SecretRef.Store stored
                    && stored.secretId().equals(txn.newSecretId().orElseThrow());
        }
        return value.orElseThrow().secretRef() instanceof SecretRef.Env;
    }

    private Snapshot readIndex() { return readIndexWithoutRecovery(); }

    private Snapshot readIndexWithoutRecovery() {
        ensureLayout();
        if (!security.exists(profiles)) return new Snapshot(0, List.of(), Map.of());
        byte[] bytes = security.read(profiles, MAX_INDEX_BYTES);
        try {
            return parseIndex(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private Snapshot parseIndex(byte[] bytes) {
        JsonNode root = parse(bytes);
        requireExactFields(root, INDEX_FIELDS);
        requireInteger(root, "schemaVersion", 1, 1);
        long generation = requireLong(root, "generation", 0, Long.MAX_VALUE);
        JsonNode defaultNode = requireObject(root, "providerDefaults");
        Map<String, String> defaults = new LinkedHashMap<>();
        defaultNode.properties().forEach(entry -> {
            String provider = validatedId(entry.getKey());
            String profile = requiredText(entry.getValue());
            validatedId(profile);
            if (defaults.put(provider, profile) != null) throw corrupt();
        });
        JsonNode profileNodes = requireArray(root, "profiles");
        if (profileNodes.size() > 64) throw corrupt();
        List<CredentialProfile> values = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Map<String, Integer> perProvider = new HashMap<>();
        for (JsonNode node : profileNodes) {
            CredentialProfile profile = parseProfile(node);
            String key = profile.providerId() + "\u0000" + profile.profileId();
            if (!keys.add(key) || perProvider.merge(profile.providerId(), 1, Integer::sum) > 16) throw corrupt();
            values.add(profile);
        }
        for (var entry : defaults.entrySet()) {
            boolean found = values.stream().anyMatch(value -> value.providerId().equals(entry.getKey())
                    && value.profileId().equals(entry.getValue()));
            if (!found) throw corrupt();
        }
        return new Snapshot(generation, values, defaults);
    }

    private CredentialProfile parseProfile(JsonNode node) {
        requireOnlyFields(node, PROFILE_FIELDS);
        Set<String> required = Set.of("id", "providerId", "authMethod", "secretRef", "createdAt", "updatedAt");
        if (!node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                .containsAll(required)) throw corrupt();
        String id = text(node, "id");
        String provider = text(node, "providerId");
        if (!"API_KEY".equals(text(node, "authMethod"))) throw corrupt();
        JsonNode reference = requireObject(node, "secretRef");
        requireOnlyFields(reference, REF_FIELDS);
        String kind = text(reference, "kind");
        SecretRef ref;
        if ("STORE".equals(kind)) {
            requireExactFields(reference, Set.of("kind", "secretId"));
            ref = new SecretRef.Store(text(reference, "secretId"));
        } else if ("ENV".equals(kind)) {
            requireExactFields(reference, Set.of("kind", "variableName"));
            ref = new SecretRef.Env(text(reference, "variableName"));
        } else throw corrupt();
        Optional<CredentialProfile.ProbeRecord> probe = Optional.empty();
        JsonNode probeNode = node.get("lastProbe");
        if (probeNode != null && !probeNode.isNull()) {
            requireExactFields(probeNode, Set.of("code", "probedAt", "definitionDigest", "modelId"));
            probe = Optional.of(new CredentialProfile.ProbeRecord(text(probeNode, "code"),
                    Instant.parse(text(probeNode, "probedAt")), text(probeNode, "definitionDigest"),
                    text(probeNode, "modelId")));
        }
        try {
            return new CredentialProfile(id, provider, ref, Instant.parse(text(node, "createdAt")),
                    Instant.parse(text(node, "updatedAt")), probe);
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
    }

    private Snapshot upsert(Snapshot old, CredentialProfile value, boolean setDefault, long generation) {
        List<CredentialProfile> next = new ArrayList<>();
        for (CredentialProfile profile : old.profiles()) {
            if (!(profile.providerId().equals(value.providerId())
                    && profile.profileId().equals(value.profileId()))) next.add(profile);
        }
        next.add(value);
        Map<String, String> defaults = new HashMap<>(old.providerDefaults());
        if (setDefault) defaults.put(value.providerId(), value.profileId());
        return new Snapshot(generation, next, defaults);
    }

    private void publish(Snapshot snapshot) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("generation", snapshot.generation());
        root.put("providerDefaults", snapshot.providerDefaults());
        List<Object> values = new ArrayList<>();
        snapshot.profiles().stream()
                .sorted(java.util.Comparator.comparing(CredentialProfile::providerId)
                        .thenComparing(CredentialProfile::profileId))
                .forEach(profile -> values.add(serializeProfile(profile)));
        root.put("profiles", values);
        writeJson(profiles, root, MAX_INDEX_BYTES, this::parseIndex);
    }

    private Object serializeProfile(CredentialProfile profile) {
        LinkedHashMap<String, Object> node = new LinkedHashMap<>();
        node.put("id", profile.profileId());
        node.put("providerId", profile.providerId());
        node.put("authMethod", "API_KEY");
        node.put("secretRef", profile.secretRef() instanceof SecretRef.Store store
                ? Map.of("kind", "STORE", "secretId", store.secretId())
                : Map.of("kind", "ENV", "variableName", ((SecretRef.Env) profile.secretRef()).variableName()));
        node.put("createdAt", profile.createdAt().toString());
        node.put("updatedAt", profile.updatedAt().toString());
        profile.lastProbe().ifPresent(probe -> node.put("lastProbe", Map.of(
                "code", probe.code(), "probedAt", probe.probedAt().toString(),
                "definitionDigest", probe.definitionDigest(), "modelId", probe.modelId())));
        return node;
    }

    private void writeSecret(String id, char[] chars) {
        LinkedHashMap<String, Object> node = new LinkedHashMap<>();
        node.put("schemaVersion", 1);
        node.put("secretId", id);
        node.put("kind", "API_KEY");
        // Jackson 边界需要短生命周期 String；序列化字节和调用方 char[] 都在本方法边界清理。
        node.put("value", new String(chars));
        writeJson(secretPath(id), node, MAX_SECRET_BYTES, bytes -> parseSecret(bytes, id).close());
        node.put("value", "<cleared>");
    }

    private SecretMaterial parseSecret(byte[] bytes, String expectedId) {
        JsonNode root = parse(bytes);
        requireExactFields(root, SECRET_FIELDS);
        requireInteger(root, "schemaVersion", 1, 1);
        if (!expectedId.equals(text(root, "secretId")) || !"API_KEY".equals(text(root, "kind"))) throw corrupt();
        try {
            return new SecretMaterial(text(root, "value").toCharArray());
        } catch (IllegalArgumentException invalid) {
            throw corrupt();
        }
    }

    private void writeTxn(Txn txn) {
        LinkedHashMap<String, Object> node = new LinkedHashMap<>();
        node.put("schemaVersion", 1);
        node.put("operation", txn.operation().name());
        node.put("providerId", txn.providerId());
        node.put("profileId", txn.profileId());
        txn.oldSecretId().ifPresent(value -> node.put("oldSecretId", value));
        txn.newSecretId().ifPresent(value -> node.put("newSecretId", value));
        node.put("expectedGeneration", txn.expectedGeneration());
        node.put("phase", txn.phase().name());
        writeJson(transaction, node, MAX_TXN_BYTES, this::parseTxn);
    }

    private Optional<Txn> readTxn() {
        if (!security.exists(transaction)) return Optional.empty();
        byte[] bytes = security.read(transaction, MAX_TXN_BYTES);
        try {
            return Optional.of(parseTxn(bytes));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private Txn parseTxn(byte[] bytes) {
        JsonNode node = parse(bytes);
        requireOnlyFields(node, TXN_FIELDS);
        requireInteger(node, "schemaVersion", 1, 1);
        try {
            Operation operation = Operation.valueOf(text(node, "operation"));
            String provider = text(node, "providerId");
            String profile = text(node, "profileId");
            Optional<String> oldId = optionalText(node, "oldSecretId").map(SecretRef.Store::new)
                    .map(SecretRef.Store::secretId);
            Optional<String> newId = optionalText(node, "newSecretId").map(SecretRef.Store::new)
                    .map(SecretRef.Store::secretId);
            long generation = requireLong(node, "expectedGeneration", 0, Long.MAX_VALUE);
            Phase phase = Phase.valueOf(text(node, "phase"));
            Set<String> expected = new HashSet<>(Set.of("schemaVersion", "operation", "providerId", "profileId",
                    "expectedGeneration", "phase"));
            oldId.ifPresent(ignored -> expected.add("oldSecretId"));
            newId.ifPresent(ignored -> expected.add("newSecretId"));
            requireExactFields(node, expected);
            return new Txn(operation, provider, profile, oldId, newId, generation, phase);
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
    }

    private void clearTxn() { if (security.exists(transaction)) security.delete(transaction); }

    private void cleanupOrphans(Snapshot index, Set<String> retained) {
        Set<String> referenced = new HashSet<>(retained);
        index.profiles().forEach(profile -> storedId(profile).ifPresent(referenced::add));
        for (Path file : security.list(secrets)) {
            String name = file.getFileName().toString();
            if (!name.matches("[0-9a-f]{32}\\.json")) throw corrupt();
            String id = name.substring(0, 32);
            if (!referenced.contains(id)) security.delete(file);
        }
    }

    private void writeJson(Path target, Object value, int maximum,
                           RestrictedFileSecurity.ContentValidator validator) {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(value);
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
        try {
            security.atomicWrite(target, target.resolveSibling(".tmp-" + newSecretId()), bytes,
                    maximum, validator, mover);
        } catch (RestrictedFileSecurity.AtomicMoveUnavailableException unavailable) {
            throw failure(AUTH_STORE_INSECURE, false);
        } catch (SecurityException insecure) {
            throw failure(AUTH_STORE_INSECURE, false);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void ensureLayout() {
        try {
            security.ensureDirectory(authRoot);
            security.ensureDirectory(secrets);
            security.ensureFile(lockFile);
        } catch (SecurityException insecure) {
            throw failure(AUTH_STORE_INSECURE, false);
        }
    }

    private <T> T locked(CancellationToken cancellation, OperationCall<T> operation) {
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        long deadline = System.nanoTime() + LOCK_TIMEOUT_NANOS;
        boolean acquired = false;
        try {
            while (!(acquired = processLock.tryLock(LOCK_POLL_MILLIS, TimeUnit.MILLISECONDS))) {
                requireActive(cancellation, deadline);
            }
            requireActive(cancellation, deadline);
            ensureLayout();
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                while (true) {
                    requireActive(cancellation, deadline);
                    try {
                        FileLock lease = channel.tryLock();
                        if (lease != null) {
                            try (lease) { return operation.run(); }
                        }
                    } catch (java.nio.channels.OverlappingFileLockException active) {
                        // 同 JVM 的另一 store 实例持有锁，继续响应取消与 deadline。
                    }
                    Thread.sleep(LOCK_POLL_MILLIS);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(AUTH_CANCELLED, true);
        } catch (java.io.IOException failure) {
            throw failure(AUTH_STORE_LOCKED, true);
        } finally {
            if (acquired) processLock.unlock();
        }
    }

    private static void requireActive(CancellationToken cancellation, long deadline) {
        if (cancellation.isCancellationRequested()) throw failure(AUTH_CANCELLED, true);
        if (System.nanoTime() - deadline >= 0) throw failure(AUTH_STORE_LOCKED, true);
    }

    private void ensureCapacity(Snapshot snapshot, String provider, String profile) {
        validatedId(provider);
        validatedId(profile);
        long same = snapshot.profiles().stream().filter(value -> value.providerId().equals(provider)
                && !value.profileId().equals(profile)).count();
        boolean replacing = snapshot.find(provider, profile).isPresent();
        if (same >= 16 || (!replacing && snapshot.profiles().size() >= 64)) {
            throw failure(AUTH_PROFILE_CONFLICT, false);
        }
    }

    private Path secretPath(String id) { return secrets.resolve(new SecretRef.Store(id).secretId() + ".json"); }
    private void deleteSecret(String id) { if (security.exists(secretPath(id))) security.delete(secretPath(id)); }
    private String newSecretId() { byte[] bytes = new byte[16]; random.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private static Optional<String> storedId(CredentialProfile profile) {
        return profile.secretRef() instanceof SecretRef.Store store ? Optional.of(store.secretId()) : Optional.empty();
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
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
    private static void requireOnlyFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || node.properties().stream()
                .anyMatch(entry -> !allowed.contains(entry.getKey()))) throw corrupt();
    }
    private static JsonNode requireObject(JsonNode node, String field) {
        JsonNode value = node.get(field); if (value == null || !value.isObject()) throw corrupt(); return value;
    }
    private static JsonNode requireArray(JsonNode node, String field) {
        JsonNode value = node.get(field); if (value == null || !value.isArray()) throw corrupt(); return value;
    }
    private static String text(JsonNode node, String field) { return requiredText(node.get(field)); }
    private static String requiredText(JsonNode value) {
        if (value == null || !value.isTextual()) throw corrupt(); return value.asText();
    }
    private static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field); return value == null ? Optional.empty() : Optional.of(requiredText(value));
    }
    private static long requireLong(JsonNode node, String field, long minimum, long maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw corrupt();
        long result = value.longValue(); if (result < minimum || result > maximum) throw corrupt(); return result;
    }
    private static void requireInteger(JsonNode node, String field, int minimum, int maximum) {
        long value = requireLong(node, field, minimum, maximum); if (value != (int) value) throw corrupt();
    }
    private static String validatedId(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw corrupt(); return value;
    }
    private static ProviderAuthException corrupt() { return failure(AUTH_STORE_CORRUPT, false); }
    private static ProviderAuthException failure(ProviderAuthException.Code code, boolean retry) {
        return new ProviderAuthException(code, CHECK_LOCAL_STORE, retry);
    }

    /** 可注入的 durable phase fault seam；异常模拟进程在该点终止。 */
    @FunctionalInterface
    interface FaultInjector {
        void after(CrashPoint point);
        static FaultInjector none() { return ignored -> { }; }
    }
    enum CrashPoint { NEW_SECRET_DURABLE, JOURNAL_SECRET_DURABLE, INDEX_PUBLISHED,
        JOURNAL_INDEX_PUBLISHED, OLD_SECRET_DELETED }
    private enum Operation { SAVE, LOGOUT }
    private enum Phase { SECRET_DURABLE, INDEX_PUBLISHED }
    private record Txn(Operation operation, String providerId, String profileId,
                       Optional<String> oldSecretId, Optional<String> newSecretId,
                       long expectedGeneration, Phase phase) {
        private Txn {
            validatedId(providerId); validatedId(profileId);
            Objects.requireNonNull(oldSecretId); Objects.requireNonNull(newSecretId);
            if (expectedGeneration < 0) throw corrupt();
        }
        private Txn withPhase(Phase value) {
            return new Txn(operation, providerId, profileId, oldSecretId, newSecretId, expectedGeneration, value);
        }
    }
    @FunctionalInterface private interface OperationCall<T> { T run(); }
}
