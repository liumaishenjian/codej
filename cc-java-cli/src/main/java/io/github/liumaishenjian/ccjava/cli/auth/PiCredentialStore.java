package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException.Action.CHECK_LOCAL_STORE;
import static io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException.Code.*;

/**
 * ADR-100 的独立 Pi 持久层：元数据、typed 秘密和 phase journal 位于 auth/pi。
 *
 * <p>绝不访问旧 profiles.v1.json 或旧 secrets。网络 modify 持有稳定 SHA-256 映射的
 * 64 个条带之一；索引锁只包围本地恢复/CAS/发布。锁文件永不删除。使用固定 JVM Semaphore
 * 与 FileLock 而非线程亲和 ReentrantLock，事务可跨线程顺序调用；finish/close 同步且终态
 * 只发生一次。JVM 的 64+1 个文件锁许可还会保守串行不同 userHome 的同条带；另一个固定
 * 布局许可仅保护目录初始化，其排队同样检查取消和截止时间，不建立无界身份映射。
 * 同一 JVM 的等待者不打开已占用锁文件，避免 POSIX 关闭其他 channel 意外释放进程文件锁。
 * 调用方必须在断连/取消时 close，锁等待有截止时间，但本层不执行网络、不自动关闭仍在
 * 计算中的事务。此处 delete 只是持久撤销，不等于已接入 lease fence/drain 或全局注销。</p>
 *
 * <p>saveLogin 借用材料并内部复制，调用方仍负责 close。所有返回材料独立拥有且必须
 * close。snapshot/list 只解析元数据；恢复根据索引与 journal 判断发布事实，不读取秘密，
 * 不重放外部操作。私有秘密封装严格绑定完整元数据（含引用、身份、代次和修订），文件
 * 上限 32 KiB，内部 typed 材料仍为 24576 字节；不迁移任何旧格式。安全检查和所有内容 IO
 * 复用 RestrictedFileSecurity。取消保证覆盖锁排队，不声称可中断任意 OS 文件 IO。</p>
 */
public final class PiCredentialStore {
    static final int MAX_INDEX_BYTES = 256 * 1024;
    private static final int MAX_JOURNAL_BYTES = 16 * 1024;
    private static final int MAX_SECRET_BYTES = 32 * 1024;
    private static final java.util.concurrent.Semaphore LAYOUT_MONITOR = new java.util.concurrent.Semaphore(1, true);
    private static final java.util.concurrent.Semaphore[] PROCESS_LOCKS = java.util.stream.IntStream.range(0, 65)
            .mapToObj(ignored -> new java.util.concurrent.Semaphore(1, true)).toArray(java.util.concurrent.Semaphore[]::new);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final RestrictedFileSecurity security;
    private final Path root;
    private final Path secrets;
    private final Path index;
    private final Path journal;
    private final Duration lockTimeout;
    private final RestrictedFileSecurity.AtomicMover mover;
    private final FaultInjector faults;

    /**
     * 使用受限用户目录与五秒锁等待窗口构造 store。
     * @param userHome 已解析的可信用户目录
     */
    public PiCredentialStore(Path userHome) {
        this(new RestrictedFileSecurity(userHome), Duration.ofSeconds(5),
                RestrictedFileSecurity.AtomicMover.system(), point -> { });
    }

    PiCredentialStore(RestrictedFileSecurity security, Duration lockTimeout,
                      RestrictedFileSecurity.AtomicMover mover, FaultInjector faults) {
        if (security == null || lockTimeout == null || lockTimeout.isNegative() || lockTimeout.isZero()
                || lockTimeout.compareTo(Duration.ofMinutes(5)) > 0 || mover == null || faults == null) {
            throw new IllegalArgumentException("PI_STORE_INVALID");
        }
        this.security = security; this.lockTimeout = lockTimeout; this.mover = mover; this.faults = faults;
        this.root = security.root().resolve("auth/pi"); this.secrets = root.resolve("secrets");
        this.index = root.resolve("index.v1.json"); this.journal = root.resolve(".txn.v1.json");
    }

    /**
     * 非秘密索引项。ENV_REF 只有 variableName，其他种类只有不透明 secretRef。
     * @param identity 身份键
     * @param authEpoch 显式登录分配的正代次；刷新不变
     * @param materialRevision 本身份的正材料修订
     * @param kind 材料种类
     * @param secretRef 可选的随机秘密引用
     * @param variableName 可选的环境变量名，绝不含值
     */
    public record Metadata(PiCredentialIdentity identity, long authEpoch, long materialRevision,
                           PiCredentialMaterial.Kind kind, Optional<String> secretRef, Optional<String> variableName) {
        /**
         * 校验 typed 引用和计数不变量。
         * @param identity 身份
         * @param authEpoch 登录代次
         * @param materialRevision 材料修订
         * @param kind 材料种类
         * @param secretRef 不透明引用
         * @param variableName 环境变量名
         */
        public Metadata {
            if (identity == null || authEpoch < 1 || materialRevision < 1 || kind == null
                    || secretRef == null || variableName == null
                    || (kind == PiCredentialMaterial.Kind.OAUTH) != (identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH)
                    || (kind == PiCredentialMaterial.Kind.ENV_REF) != variableName.isPresent()
                    || (kind != PiCredentialMaterial.Kind.ENV_REF) != secretRef.isPresent()) throw corrupt();
            secretRef.ifPresent(PiCredentialStore::ref);
            variableName.ifPresent(value -> {
                if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw corrupt();
            });
        }
        @Override public String toString() { return "<redacted>"; }
    }

    /**
     * 不携带任何秘密的全索引快照。
     * @param generation 全局发布代次，0 表示尚未发布
     * @param profiles 不可变元数据列表
     * @param providerDefaults 每路由的默认 profile
     */
    public record Snapshot(long generation, List<Metadata> profiles, Map<String, String> providerDefaults) {
        /**
         * 防御复制并验证索引容量、唯一身份与默认引用。
         * @param generation 全索引代次
         * @param profiles 元数据
         * @param providerDefaults 默认 profile
         */
        public Snapshot {
            if (generation < 0 || profiles == null || providerDefaults == null) throw corrupt();
            profiles = List.copyOf(profiles); providerDefaults = Map.copyOf(providerDefaults);
            if (profiles.size() > 64) throw corrupt();
            Set<PiCredentialIdentity> seen = new HashSet<>();
            Set<String> refs = new HashSet<>();
            Map<String, Integer> count = new HashMap<>();
            for (Metadata entry : profiles) {
                if (!seen.add(entry.identity()) || entry.authEpoch() > generation || entry.materialRevision() > generation
                        || count.merge(entry.identity().providerId(), 1, Integer::sum) > 16
                        || (entry.secretRef().isPresent() && !refs.add(entry.secretRef().orElseThrow()))) throw corrupt();
            }
            for (var entry : providerDefaults.entrySet()) {
                if (profiles.stream().noneMatch(value -> value.identity().providerId().equals(entry.getKey())
                        && value.identity().profileId().equals(entry.getValue()))) throw corrupt();
            }
        }
        /**
         * 按完整身份查找，不回退到其他认证方法或 ENV。
         * @param identity 可信目标身份
         * @return 当前元数据
         */
        public Optional<Metadata> find(PiCredentialIdentity identity) {
            return profiles.stream().filter(value -> value.identity().equals(identity)).findFirst();
        }
        @Override public String toString() { return "<redacted>"; }
    }

    /**
     * 在短索引锁内恢复并只读取元数据；损坏或缺失的被引用秘密不会被读取。
     * @param cancellation 锁等待取消令牌
     * @return 不含秘密的快照
     */
    public Snapshot snapshot(CancellationToken cancellation) { return indexed(cancellation, this::readIndex); }

    /**
     * 元数据列表，与 snapshot 使用同一只读秘密边界。
     * @param cancellation 锁等待取消令牌
     * @return 不可变元数据列表
     */
    public List<Metadata> list(CancellationToken cancellation) { return snapshot(cancellation).profiles(); }

    /**
     * 短事务中按当前身份代次读取独立材料，缺失秘密失败关闭、不回退 ENV。
     * @param identity 可信身份
     * @param expectedAuthEpoch 调用方已捕获的身份代次
     * @param cancellation 取消令牌
     * @return 调用方必须 close 的材料
     */
    public PiCredentialMaterial read(PiCredentialIdentity identity, long expectedAuthEpoch, CancellationToken cancellation) {
        return indexed(cancellation, () -> material(requireEpoch(readIndex(), identity, expectedAuthEpoch)));
    }

    /**
     * 显式登录保守 CAS：必须在输入等待前捕获全索引代次，变化时拒绝而不自动重基。
     * 不持有网络条带锁；新 authEpoch 会使旧 modify 的提交失败。
     * @param identity 完整身份
     * @param supplied 借用的 typed 材料，调用方仍负责 close
     * @param expectedIndexGeneration 输入等待前捕获的全索引代次，负数拒绝
     * @param setDefault 是否设置该路由默认 profile
     * @param cancellation 取消令牌
     * @return 已发布的非秘密元数据
     */
    public Metadata saveLogin(PiCredentialIdentity identity, PiCredentialMaterial supplied,
                              long expectedIndexGeneration, boolean setDefault, CancellationToken cancellation) {
        if (supplied == null) throw new IllegalArgumentException("PI_MATERIAL_INVALID");
        try (PiCredentialMaterial owned = supplied.copy()) {
            owned.requireIdentity(identity);
            return indexed(cancellation, () -> {
                Snapshot old = readIndex();
                if (expectedIndexGeneration < 0 || old.generation() != expectedIndexGeneration) throw conflict();
                active(cancellation, Long.MAX_VALUE);
                long generation = increment(old.generation());
                Metadata previous = old.find(identity).orElse(null);
                Metadata next = metadata(identity, generation, 1, owned);
                Snapshot update = upsert(old, next, setDefault, generation);
                commit(old, update, previous, next, owned, cancellation);
                return next;
            });
        }
    }

    /**
     * 获取身份条带并在短索引事务中读取当前修订。网络工作应发生在返回之后。
     * @param identity 完整身份
     * @param expectedAuthEpoch 预期登录代次
     * @param cancellation 等待及读取时的取消令牌
     * @return 必须 finish 或 close 的事务，可跨线程顺序移交
     */
    public Transaction beginModify(PiCredentialIdentity identity, long expectedAuthEpoch, CancellationToken cancellation) {
        if (identity == null) throw new IllegalArgumentException("PI_IDENTITY_INVALID");
        LockHandle stripe = acquire(root.resolve(String.format(java.util.Locale.ROOT, ".modify-%02d.lock", stripe(identity))), cancellation);
        try {
            return indexed(cancellation, () -> {
                Metadata current = requireEpoch(readIndex(), identity, expectedAuthEpoch);
                return new Transaction(stripe, current, material(current));
            });
        } catch (RuntimeException | Error failure) { stripe.close(); throw failure; }
    }

    /**
     * 持久撤销不等待网络条带；index 先发布，旧秘密随后清理。迟到 finish 不能复活身份。
     * @param identity 完整身份
     * @param expectedAuthEpoch 预期登录代次
     * @param cancellation 短索引锁取消令牌
     */
    public void delete(PiCredentialIdentity identity, long expectedAuthEpoch, CancellationToken cancellation) {
        deleteWithReceipt(identity, expectedAuthEpoch, cancellation);
    }

    /**
     * 删除并返回此事务实际发布的索引水位，供fence解除许可使用。
     * <p>不能用删除后另一次snapshot代替：期间其他身份的写入不属于本次删除证据。</p>
     * @param identity 完整身份
     * @param expectedAuthEpoch 预期登录代次
     * @param cancellation 短索引锁取消令牌
     * @return 本次删除的索引generation，只有完整提交成功后返回
     */
    public long deleteWithReceipt(PiCredentialIdentity identity, long expectedAuthEpoch, CancellationToken cancellation) {
        return indexed(cancellation, () -> {
            Snapshot old = readIndex();
            Metadata previous = requireEpoch(old, identity, expectedAuthEpoch);
            Map<String, String> defaults = new HashMap<>(old.providerDefaults());
            defaults.remove(identity.providerId(), identity.profileId());
            Snapshot update = new Snapshot(increment(old.generation()), old.profiles().stream()
                    .filter(value -> !value.identity().equals(identity)).toList(), defaults);
            commit(old, update, previous, null, null, cancellation);
            return update.generation();
        });
    }

    /** finish 的封闭变更类型；网络刷新不能借此执行登录或删除。 */
    public enum Change {
        /** 不替换，但仍校验 epoch 与 revision。 */
        KEEP,
        /** 仅同 epoch、同 accountId 的 OAuth 旋转。 */
        PUT
    }

    /**
     * 持有跨进程条带的有界身份事务；所有公开方法同步，允许不同线程顺序调用。
     * snapshot 返回独立副本，close 擦除内部材料但不触碰调用方副本。finish 无论成功或失败
     * 都进入终态并释放锁；发布后故障可能已提交，调用方须重新读取权威状态。
     */
    public final class Transaction implements AutoCloseable {
        private final LockHandle stripe;
        private final Metadata initial;
        private final PiCredentialMaterial owned;
        private boolean closed;
        private Transaction(LockHandle stripe, Metadata initial, PiCredentialMaterial owned) {
            this.stripe = stripe; this.initial = initial; this.owned = owned;
        }
        /**
         * 查询事务绑定的 epoch 和 revision；不读取磁盘秘密。
         * @return 不含秘密的事务初始元数据
         */
        public Metadata metadata() { return initial; }
        /**
         * 复制内部材料，关闭该副本不会破坏事务提交校验。
         * @return 调用方必须 close 的独立材料快照
         */
        public synchronized PiCredentialMaterial snapshot() { requireOpen(); return owned.copy(); }

        /**
         * 重读最新全索引后 CAS 并合并；不覆盖其他身份更新。PUT 仅允许 OAuth 正常旋转。
         * @param change KEEP 或 PUT
         * @param candidate PUT 时借用的材料；KEEP 必须为 null，调用方负责 close
         * @param cancellation 本地提交取消令牌；可由 RPC 使用独立清理窗口令牌
         * @return 持久 ACK 对应的权威材料，调用方必须 close
         */
        public synchronized PiCredentialMaterial finish(Change change, PiCredentialMaterial candidate,
                                                         CancellationToken cancellation) {
            requireOpen();
            try {
                if (change == null || (change == Change.PUT) != (candidate != null)) throw conflict();
                try (PiCredentialMaterial replacement = candidate == null ? null : candidate.copy()) {
                    if (replacement != null) {
                        replacement.requireIdentity(initial.identity());
                        if (initial.kind() != PiCredentialMaterial.Kind.OAUTH || !owned.sameAccount(replacement)) throw conflict();
                    }
                    return indexed(cancellation, () -> {
                        Snapshot old = readIndex();
                        Metadata current = requireEpoch(old, initial.identity(), initial.authEpoch());
                        if (!current.equals(initial)) throw conflict();
                        if (change == Change.KEEP) return material(current);
                        Metadata next = PiCredentialStore.this.metadata(initial.identity(), initial.authEpoch(),
                                increment(initial.materialRevision()), replacement);
                        Snapshot update = upsert(old, next, false, increment(old.generation()));
                        commit(old, update, current, next, replacement, cancellation);
                        return material(next);
                    });
                }
            } finally { close(); }
        }
        /** 取消事务，不进行任何外部重放或持久写入。 */
        public void abort() { close(); }
        /** 擦除内部快照并释放文件锁；成功关闭幂等，关闭失败在重复调用时仍报告固定码。 */
        @Override public synchronized void close() {
            if (!closed) { closed = true; owned.close(); }
            stripe.close();
        }
        private void requireOpen() { if (closed) throw new IllegalStateException("PI_TRANSACTION_CLOSED"); }
        @Override public String toString() { return "<redacted>"; }
    }

    private Metadata metadata(PiCredentialIdentity identity, long epoch, long revision, PiCredentialMaterial material) {
        boolean env = material.kind() == PiCredentialMaterial.Kind.ENV_REF;
        return new Metadata(identity, epoch, revision, material.kind(), env ? Optional.empty() : Optional.of(randomId()),
                env ? Optional.of(material.variableName()) : Optional.empty());
    }
    private Snapshot upsert(Snapshot old, Metadata next, boolean setDefault, long generation) {
        List<Metadata> values = new ArrayList<>(old.profiles().stream()
                .filter(value -> !value.identity().equals(next.identity())).toList());
        if (values.size() >= 64 || values.stream().filter(value -> value.identity().providerId()
                .equals(next.identity().providerId())).count() >= 16) throw error(AUTH_PROFILE_CONFLICT);
        values.add(next);
        Map<String, String> defaults = new HashMap<>(old.providerDefaults());
        if (setDefault) defaults.put(next.identity().providerId(), next.identity().profileId());
        return new Snapshot(generation, values, defaults);
    }
    private Metadata requireEpoch(Snapshot snapshot, PiCredentialIdentity identity, long epoch) {
        Metadata current = snapshot.find(identity).orElseThrow(PiCredentialStore::conflict);
        if (epoch < 1 || current.authEpoch() != epoch) throw conflict();
        return current;
    }
    private PiCredentialMaterial material(Metadata entry) {
        if (entry.kind() == PiCredentialMaterial.Kind.ENV_REF) return PiCredentialMaterial.envRef(entry.variableName().orElseThrow());
        Path file = secretPath(entry.secretRef().orElseThrow());
        if (!security.exists(file)) throw error(AUTH_SECRET_UNAVAILABLE);
        byte[] bytes = security.read(file, MAX_SECRET_BYTES);
        try { return parseSecret(bytes, entry); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    /** 私有封装将材料绑定到精确索引项；不可将同 kind 的其他身份或旧修订当作当前材料。 */
    private PiCredentialMaterial parseSecret(byte[] bytes, Metadata expected) {
        byte[] inner = null;
        PiCredentialMaterial result = null;
        try {
            if (bytes.length > MAX_SECRET_BYTES) throw corrupt();
            JsonNode node = parse(bytes);
            fields(node, Set.of("version", "metadata", "material")); version(node);
            if (!parseMetadata(node.get("metadata")).equals(expected) || !node.get("material").isObject()) throw corrupt();
            inner = JSON.writeValueAsBytes(node.get("material"));
            result = PiCredentialMaterial.fromJson(inner);
            result.requireIdentity(expected.identity());
            if (result.kind() != expected.kind()) throw corrupt();
            return result;
        } catch (RuntimeException failure) { if (result != null) result.close(); throw corrupt(); }
        finally { if (inner != null) Arrays.fill(inner, (byte) 0); }
    }

    /** 写前与原子写入校验器采用同一预期元数据；中间材料及封装字节均由 finally 擦除。 */
    private void writeSecret(Path target, Metadata expected, PiCredentialMaterial material) {
        byte[] inner = material.copyJson();
        try {
            writeJson(target, Map.of("version", 1, "metadata", encodeMetadata(expected), "material", parse(inner)),
                    MAX_SECRET_BYTES, value -> parseSecret(value, expected).close());
        } finally { Arrays.fill(inner, (byte) 0); }
    }

    private void commit(Snapshot before, Snapshot after, Metadata old, Metadata next,
                        PiCredentialMaterial material, CancellationToken cancellation) {
        active(cancellation, Long.MAX_VALUE);
        if (next != null && next.secretRef().isPresent()) {
            Path target = secretPath(next.secretRef().orElseThrow());
            if (security.exists(target)) throw conflict();
            writeSecret(target, next, material);
            faults.after(CrashPoint.NEW_SECRET_DURABLE);
        }
        Journal pending = new Journal(before.generation(), old, next, false);
        writeJournal(pending); faults.after(CrashPoint.JOURNAL_SECRET_DURABLE);
        // journal 已 durable，但在 index 发布前取消仍可由恢复清理新秘密。
        active(cancellation, Long.MAX_VALUE);
        writeIndex(after); faults.after(CrashPoint.INDEX_PUBLISHED);
        writeJournal(new Journal(before.generation(), old, next, true));
        faults.after(CrashPoint.JOURNAL_INDEX_PUBLISHED);
        if (old != null) old.secretRef().ifPresent(value -> security.delete(secretPath(value)));
        faults.after(CrashPoint.OLD_SECRET_DELETED);
        security.delete(journal);
        cleanup(after);
    }

    private void recover() {
        Snapshot truth = readIndex();
        if (security.exists(journal)) {
            byte[] bytes = security.read(journal, MAX_JOURNAL_BYTES);
            Journal pending;
            try { pending = parseJournal(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
            PiCredentialIdentity identity = pending.old() != null ? pending.old().identity() : pending.next().identity();
            Metadata actual = truth.find(identity).orElse(null);
            if (truth.generation() == pending.generation() && java.util.Objects.equals(actual, pending.old())) {
                if (pending.next() != null) pending.next().secretRef().ifPresent(value -> security.delete(secretPath(value)));
            } else if (truth.generation() == increment(pending.generation()) && java.util.Objects.equals(actual, pending.next())) {
                if (pending.old() != null) pending.old().secretRef().ifPresent(value -> security.delete(secretPath(value)));
            } else throw conflict();
            security.delete(journal);
        }
        cleanup(truth);
    }
    private void cleanup(Snapshot truth) {
        Set<String> retained = truth.profiles().stream().flatMap(value -> value.secretRef().stream()).collect(Collectors.toSet());
        for (Path file : security.list(secrets)) {
            String name = file.getFileName().toString();
            if (name.matches("\\.tmp-[0-9a-f]{32}")) security.delete(file);
            else if (!name.matches("[0-9a-f]{32}\\.json")) throw corrupt();
            else if (!retained.contains(name.substring(0, 32))) security.delete(file);
        }
        for (Path file : security.list(root)) {
            if (file.getFileName().toString().matches("\\.tmp-[0-9a-f]{32}")) security.delete(file);
        }
    }
    private Snapshot readIndex() {
        if (!security.exists(index)) return new Snapshot(0, List.of(), Map.of());
        byte[] bytes = security.read(index, MAX_INDEX_BYTES);
        try { return parseIndex(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private Snapshot parseIndex(byte[] bytes) {
        try {
            JsonNode node = parse(bytes);
            fields(node, Set.of("version", "generation", "profiles", "providerDefaults")); version(node);
            JsonNode entries = node.get("profiles"); JsonNode defaults = node.get("providerDefaults");
            if (!entries.isArray() || entries.size() > 64 || !defaults.isObject() || defaults.size() > 4) throw corrupt();
            List<Metadata> values = new ArrayList<>();
            for (JsonNode entry : entries) values.add(parseMetadata(entry));
            Map<String, String> mapping = new HashMap<>();
            for (var entry : defaults.properties()) {
                if (!entry.getValue().isTextual()) throw corrupt();
                mapping.put(entry.getKey(), entry.getValue().asText());
            }
            return new Snapshot(number(node, "generation", 0), values, mapping);
        } catch (RuntimeException failure) { throw corrupt(); }
    }
    private Metadata parseMetadata(JsonNode node) {
        fields(node, Set.of("backend", "providerId", "authMethod", "profileId", "authEpoch", "materialRevision",
                "kind", "secretRef", "variableName"));
        PiCredentialIdentity identity = new PiCredentialIdentity(text(node, "backend"), text(node, "providerId"),
                PiCredentialIdentity.AuthMethod.valueOf(text(node, "authMethod")), text(node, "profileId"));
        return new Metadata(identity, number(node, "authEpoch", 1), number(node, "materialRevision", 1),
                PiCredentialMaterial.Kind.valueOf(text(node, "kind")), optional(node, "secretRef"), optional(node, "variableName"));
    }
    private Map<String, Object> encodeMetadata(Metadata value) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("backend", value.identity().backend()); node.put("providerId", value.identity().providerId());
        node.put("authMethod", value.identity().authMethod().name()); node.put("profileId", value.identity().profileId());
        node.put("authEpoch", value.authEpoch()); node.put("materialRevision", value.materialRevision());
        node.put("kind", value.kind().name()); node.put("secretRef", value.secretRef().orElse(null));
        node.put("variableName", value.variableName().orElse(null)); return node;
    }
    private void writeIndex(Snapshot value) {
        writeJson(index, Map.of("version", 1, "generation", value.generation(), "providerDefaults", value.providerDefaults(),
                "profiles", value.profiles().stream().map(this::encodeMetadata).toList()), MAX_INDEX_BYTES, this::parseIndex);
    }
    private record Journal(long generation, Metadata old, Metadata next, boolean published) { }
    private void writeJournal(Journal value) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("version", 1); node.put("generation", value.generation()); node.put("published", value.published());
        node.put("old", value.old() == null ? null : encodeMetadata(value.old()));
        node.put("next", value.next() == null ? null : encodeMetadata(value.next()));
        writeJson(journal, node, MAX_JOURNAL_BYTES, this::parseJournal);
    }
    private Journal parseJournal(byte[] bytes) {
        try {
            JsonNode node = parse(bytes); fields(node, Set.of("version", "generation", "published", "old", "next")); version(node);
            long generation = number(node, "generation", 0); increment(generation);
            Metadata old = node.get("old").isNull() ? null : parseMetadata(node.get("old"));
            Metadata next = node.get("next").isNull() ? null : parseMetadata(node.get("next"));
            if (old == null && next == null || !node.get("published").isBoolean()
                    || old != null && next != null && (!old.identity().equals(next.identity())
                    || old.secretRef().isPresent() && old.secretRef().equals(next.secretRef()))
                    || old != null && (old.authEpoch() > generation || old.materialRevision() > generation)
                    || next != null && (next.authEpoch() > generation + 1 || next.materialRevision() > generation + 1)) throw corrupt();
            return new Journal(generation, old, next, node.get("published").booleanValue());
        } catch (RuntimeException failure) { throw corrupt(); }
    }
    private void writeJson(Path target, Object value, int maximum, RestrictedFileSecurity.ContentValidator validator) {
        byte[] bytes;
        try { bytes = JSON.writeValueAsBytes(value); } catch (RuntimeException failure) { throw corrupt(); }
        try { write(target, bytes, maximum, validator); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private void write(Path target, byte[] bytes, int maximum, RestrictedFileSecurity.ContentValidator validator) {
        security.atomicWrite(target, target.resolveSibling(".tmp-" + randomId()), bytes, maximum, validator, mover);
    }
    private <T> T indexed(CancellationToken cancellation, LocalCall<T> action) {
        try (LockHandle ignored = acquire(root.resolve(".index.lock"), cancellation)) {
            recover(); active(cancellation, Long.MAX_VALUE); return action.run();
        } catch (SecurityException failure) { throw error(AUTH_STORE_INSECURE); }
    }
    private LockHandle acquire(Path path, CancellationToken cancellation) {
        long deadline = System.nanoTime() + lockTimeout.toNanos();
        FileChannel channel = null;
        String name = path.getFileName().toString();
        java.util.concurrent.Semaphore process = PROCESS_LOCKS[name.equals(".index.lock") ? 64
                : Integer.parseInt(name.substring(8, 10))];
        boolean acquired = false;
        try {
            do {
                active(cancellation, deadline);
                acquired = process.tryAcquire(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            } while (!acquired);
            active(cancellation, deadline);
            while (true) {
                active(cancellation, deadline);
                if (LAYOUT_MONITOR.tryAcquire(10, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
            }
            try {
                active(cancellation, deadline);
                ensureLockLayout(path);
            } finally { LAYOUT_MONITOR.release(); }
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            while (true) {
                active(cancellation, deadline);
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        // 安全属性由公共边界验证；额外复核锁文件未在打开/获得锁期间被替换。
                        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        security.ensureDirectory(path.getParent());
                        if (!security.exists(path) || !sameLockIdentity(before, after)) {
                            lock.release(); throw error(AUTH_STORE_INSECURE);
                        }
                        LockHandle handle = new LockHandle(channel, lock, process);
                        channel = null; acquired = false; return handle;
                    }
                } catch (OverlappingFileLockException busy) {
                    // 同 JVM 的任何 store 实例也经过相同文件锁，不建立无界身份 map。
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw error(AUTH_CANCELLED);
        } catch (SecurityException insecure) { throw error(AUTH_STORE_INSECURE); }
        catch (IOException failure) { throw error(AUTH_STORE_LOCKED); }
        finally {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { /* 固定错误，无路径。 */ }
            if (acquired) process.release();
        }
    }
    private void ensureLockLayout(Path path) {
        try { security.ensureDirectory(secrets); security.ensureFile(path); }
        catch (SecurityException first) {
            // 不同 JVM 首次创建可能竞争；只重做完整验证，不更改既有权限、不降级 channel。
            security.ensureDirectory(secrets); security.ensureFile(path);
        }
    }
    // 与 RestrictedFileSecurity 的 identity fallback 相同：Windows Provider 可能不公开 fileKey。
    // 不把文件名或 owner 名字当作 inode 证明；安全属性仍在公共边界中完整复核。
    private static boolean sameLockIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        return before.fileKey() != null ? before.fileKey().equals(after.fileKey())
                : after.fileKey() == null && before.creationTime().equals(after.creationTime())
                && before.isRegularFile() && after.isRegularFile();
    }
    private static final class LockHandle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final java.util.concurrent.Semaphore process;
        private boolean closed;
        private boolean closeFailed;
        private LockHandle(FileChannel channel, FileLock lock, java.util.concurrent.Semaphore process) {
            this.channel = channel; this.lock = lock; this.process = process;
        }
        @Override public synchronized void close() {
            if (closed) {
                if (closeFailed) throw error(AUTH_STORE_LOCKED);
                return;
            }
            closed = true;
            try { lock.release(); } catch (IOException ignored) { /* channel close 同样释放锁。 */ }
            try { channel.close(); } catch (IOException failure) { closeFailed = true; throw error(AUTH_STORE_LOCKED); }
            finally { process.release(); }
        }
    }
    static int stripe(PiCredentialIdentity identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.lockKey().getBytes(StandardCharsets.UTF_8));
            return Byte.toUnsignedInt(digest[0]) & 63;
        } catch (NoSuchAlgorithmException failure) { throw error(AUTH_STORE_INSECURE); }
    }
    private static void active(CancellationToken cancellation, long deadline) {
        if (cancellation == null) throw new IllegalArgumentException("PI_CANCELLATION_INVALID");
        if (Thread.currentThread().isInterrupted() || cancellation.isCancellationRequested()
                || cancellation.remainingTime().filter(value -> value.isZero() || value.isNegative()).isPresent()) {
            throw error(AUTH_CANCELLED);
        }
        if (deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0) throw error(AUTH_STORE_LOCKED);
    }
    private Path secretPath(String value) { return secrets.resolve(ref(value) + ".json"); }
    private static String ref(String value) { if (!value.matches("[0-9a-f]{32}")) throw corrupt(); return value; }
    private static String randomId() { return HexFormat.of().formatHex(ByteBuffer.allocate(16)
            .putLong(UUID.randomUUID().getMostSignificantBits()).putLong(UUID.randomUUID().getLeastSignificantBits()).array()); }
    private static long increment(long value) { if (value < 0 || value == Long.MAX_VALUE) throw conflict(); return value + 1; }
    private static JsonNode parse(byte[] bytes) {
        try {
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (source.startsWith("\ufeff")) throw corrupt();
            return JSON.readTree(source);
        } catch (java.nio.charset.CharacterCodingException | RuntimeException failure) { throw corrupt(); }
    }
    private static void fields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !node.properties().stream().map(Map.Entry::getKey)
                .collect(Collectors.toSet()).equals(fields)) throw corrupt();
    }
    private static void version(JsonNode node) { if (number(node, "version", 1) != 1) throw corrupt(); }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key); if (value == null || !value.isTextual()) throw corrupt(); return value.asText();
    }
    private static Optional<String> optional(JsonNode node, String key) {
        return node.get(key).isNull() ? Optional.empty() : Optional.of(text(node, key));
    }
    private static long number(JsonNode node, String key, long minimum) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) throw corrupt();
        return value.longValue();
    }
    private static ProviderAuthException conflict() { return error(AUTH_TRANSACTION_CONFLICT); }
    private static ProviderAuthException corrupt() { return error(AUTH_STORE_CORRUPT); }
    private static ProviderAuthException error(ProviderAuthException.Code code) {
        return new ProviderAuthException(code, CHECK_LOCAL_STORE, code == AUTH_CANCELLED || code == AUTH_STORE_LOCKED || code == AUTH_TRANSACTION_CONFLICT);
    }
    /** 独立 durable phase seam，仅测试注入崩溃；不重放任何网络操作。 */
    @FunctionalInterface interface FaultInjector { void after(CrashPoint point); }
    enum CrashPoint { NEW_SECRET_DURABLE, JOURNAL_SECRET_DURABLE, INDEX_PUBLISHED, JOURNAL_INDEX_PUBLISHED, OLD_SECRET_DELETED }
    @FunctionalInterface private interface LocalCall<T> { T run(); }
}
