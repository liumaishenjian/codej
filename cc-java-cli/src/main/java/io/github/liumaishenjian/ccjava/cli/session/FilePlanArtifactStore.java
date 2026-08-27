package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.PlanArtifactStore;
import io.github.liumaishenjian.ccjava.core.PlanArtifactStoreException;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanLifecyclePolicy;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 在 Session 私有目录中保存项目自有 Markdown {@link PlanArtifact} 的边缘 Adapter。
 *
 * <p>每个 revision 的 Markdown 写入不可变 generation 文件；唯一权威入口是固定文件名
 * {@code plan.manifest.json}。发布先以同目录 {@code CREATE_NEW} 写入并 force generation 与
 * manifest 暂存文件，最后只原子替换 manifest。读者先读取 manifest，再读取它引用的 generation，
 * 因而崩溃只能留下仍指向完整旧版的 manifest，或已经完整指向新版的 manifest；generation
 * 已落盘但 manifest 尚未切换只是可忽略 orphan，不构成可见 revision。两个 rename 从不被当作事务。</p>
 *
 * <p>目录和文件名均由已验证的 Session 身份与固定格式派生，不接受调用方路径。每次访问拒绝
 * Symlink、Junction/重解析身份变化和非常规文件。Session journal 是跨 journal/artifact 恢复的
 * canonical source；本 store 只是可重建投影，不授予执行权限或自动重放副作用。</p>
 *
 * @since 0.1.0
 */
public final class FilePlanArtifactStore implements PlanArtifactStore {
    static final String MANIFEST_FILE = "plan.manifest.json";
    private static final String GENERATION_PREFIX = "plan-r";
    private static final String GENERATION_SUFFIX = ".md";
    private static final Pattern GENERATION = Pattern.compile(
            "plan-r([1-9][0-9]{0,18})-([0-9a-f]{64})\\.md");
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final int MAX_CLEANUP_ENTRIES = 64;
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "planId", "sessionId", "revision", "contentFile", "contentDigest",
            "status", "createdAt", "updatedAt", "executionBrief", "verificationResumeReview", "evidenceLedger");

    private final Path sessionRoot;
    private final SessionId sessionId;
    private final PublishObserver publishObserver;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    /** 创建绑定单个 Session 的 store。 */
    public FilePlanArtifactStore(Path sessionDirectory, SessionId sessionId) {
        this(sessionDirectory, sessionId, artifact -> { });
    }

    /** 仅供同包故障注入测试观察 generation durable 边界。 */
    FilePlanArtifactStore(Path sessionDirectory, SessionId sessionId, PublishObserver publishObserver) {
        this.sessionRoot = Objects.requireNonNull(sessionDirectory, "sessionDirectory 不能为空")
                .toAbsolutePath().normalize();
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.publishObserver = Objects.requireNonNull(publishObserver, "publishObserver 不能为空");
        if (sessionRoot.getFileName() == null || !sessionRoot.getFileName().toString().equals(sessionId.value())) {
            throw failure(PlanArtifactStoreException.Code.PATH_REJECTED);
        }
        verifyDirectory();
    }

    @Override
    public synchronized Optional<PlanArtifact> load(SessionId requestedSessionId) {
        requireOwner(requestedSessionId);
        verifyDirectory();
        Path manifest = manifestPath();
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            cleanupOrphans(Optional.empty());
            return Optional.empty();
        }
        verifyRegular(manifest);
        try {
            long manifestSize = Files.size(manifest);
            if (manifestSize < 1 || manifestSize > MAX_MANIFEST_BYTES) {
                throw failure(PlanArtifactStoreException.Code.LIMIT_EXCEEDED);
            }
            BasicFileAttributes manifestBefore = attributes(manifest);
            ObjectNode node = parseManifest(Files.readAllBytes(manifest));
            requireUnchanged(manifest, manifestBefore);
            String contentFile = requiredText(node, "contentFile", 160);
            Matcher name = GENERATION.matcher(contentFile);
            if (!name.matches()) throw failure(PlanArtifactStoreException.Code.CORRUPT);
            long revision = requiredPositiveLong(node, "revision");
            String digest = requiredText(node, "contentDigest", 64);
            if (Long.parseLong(name.group(1)) != revision || !name.group(2).equals(digest)) {
                throw failure(PlanArtifactStoreException.Code.CORRUPT);
            }
            Path content = sessionRoot.resolve(contentFile).normalize();
            if (!content.getParent().equals(sessionRoot)
                    || !Files.exists(content, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(PlanArtifactStoreException.Code.CORRUPT);
            }
            verifyRegular(content);
            long contentSize = Files.size(content);
            if (contentSize < 1 || contentSize > PlanArtifact.MAX_CONTENT_UTF8_BYTES) {
                throw failure(PlanArtifactStoreException.Code.LIMIT_EXCEEDED);
            }
            BasicFileAttributes contentBefore = attributes(content);
            String markdown = decodeUtf8(Files.readAllBytes(content));
            requireUnchanged(content, contentBefore);
            PlanArtifact artifact = new PlanArtifact(
                    requiredText(node, "planId", 128),
                    new SessionId(requiredText(node, "sessionId", 128)),
                    revision,
                    markdown,
                    digest,
                    enumValue(node, "status"),
                    instant(node, "createdAt"),
                    instant(node, "updatedAt"),
                    node.has("executionBrief")
                            ? java.util.Optional.of(ExecutionBriefJson.decode(node.get("executionBrief"), markdown))
                            : java.util.Optional.empty(),
                    decodeVerificationResumeReview(node),
                    node.has("evidenceLedger")
                            ? PlanEvidenceLedgerJson.decode(node.get("evidenceLedger"))
                            : io.github.liumaishenjian.ccjava.domain.PlanEvidenceLedger.planning(sessionId,
                                    requiredText(node, "planId", 128), instant(node, "createdAt")));
            if (!artifact.sessionId().equals(sessionId)) {
                throw failure(PlanArtifactStoreException.Code.IDENTITY_MISMATCH);
            }
            cleanupOrphans(Optional.of(artifact));
            return Optional.of(artifact);
        } catch (PlanArtifactStoreException known) {
            throw known;
        } catch (RuntimeException | IOException invalid) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    @Override
    public synchronized PlanArtifact save(
            PlanArtifact artifact, long expectedRevision, String expectedContentDigest) {
        PreparedArtifact prepared = prepare(artifact, expectedRevision, expectedContentDigest);
        commit(prepared);
        return requireExact(artifact);
    }

    @Override
    public synchronized PlanArtifact restoreMissing(PlanArtifact artifact) {
        requireArtifactOwner(artifact);
        if (load(sessionId).isPresent()) throw failure(PlanArtifactStoreException.Code.ALREADY_EXISTS);
        PreparedArtifact prepared = prepare(artifact, 0, "");
        commit(prepared);
        return requireExact(artifact);
    }

    /**
     * 在 canonical journal 已经提交后，把缺失或落后的本地投影收敛到该事实。
     * 身份不一致和损坏 manifest 仍失败关闭；合法但领先 journal 的 revision 可安全回退为 journal 指针。
     */
    synchronized PlanArtifact restoreAuthoritative(PlanArtifact artifact) {
        requireArtifactOwner(artifact);
        Optional<PlanArtifact> local = load(sessionId);
        if (local.isPresent()) {
            PlanArtifact current = local.orElseThrow();
            if (current.equals(artifact)) return current;
            if (!current.planId().equals(artifact.planId())
                    || !current.createdAt().equals(artifact.createdAt())) {
                throw failure(PlanArtifactStoreException.Code.IDENTITY_MISMATCH);
            }
        }
        ensureGeneration(artifact);
        publishObserver.generationDurable(artifact);
        commit(new PreparedArtifact(artifact, generationPath(artifact)));
        return requireExact(artifact);
    }

    /** 丢弃尚未进入 canonical journal 的合法本地 manifest；不可变 generation 留作可忽略 orphan。 */
    synchronized void discardUnjournaled(PlanArtifact expected) {
        requireArtifactOwner(expected);
        PlanArtifact current = load(sessionId)
                .orElseThrow(() -> failure(PlanArtifactStoreException.Code.NOT_FOUND));
        if (!current.equals(expected)) throw failure(PlanArtifactStoreException.Code.DIGEST_CONFLICT);
        Path manifest = manifestPath();
        Path discarded = sessionRoot.resolve(".plan-discard-" + token() + ".tmp");
        atomicMove(manifest, discarded, false);
        deleteStage(discarded);
        try {
            forceDirectory(sessionRoot);
        } catch (IOException failure) {
            throw failure(PlanArtifactStoreException.Code.IO_FAILURE);
        }
        cleanupOrphans(Optional.empty());
    }

    /**
     * 校验 CAS 并仅准备不可变 generation；尚不改变读者可见的 authoritative manifest。
     * FileSessionStore 借此在 journal append 前留下安全 orphan，而不是本地领先的可见 revision。
     */
    synchronized PreparedArtifact prepare(
            PlanArtifact artifact, long expectedRevision, String expectedContentDigest) {
        requireArtifactOwner(artifact);
        Optional<PlanArtifact> existing = load(sessionId);
        if (expectedRevision == 0) {
            if (!Objects.requireNonNull(expectedContentDigest, "expectedContentDigest 不能为空").isEmpty()) {
                throw failure(PlanArtifactStoreException.Code.DIGEST_CONFLICT);
            }
            if (existing.isPresent()) throw failure(PlanArtifactStoreException.Code.ALREADY_EXISTS);
            if (artifact.revision() != 1) throw failure(PlanArtifactStoreException.Code.STALE_REVISION);
            if (!PlanLifecyclePolicy.validInitial(artifact.status())) {
                throw failure(PlanArtifactStoreException.Code.INVALID_STATE);
            }
        } else {
            PlanArtifact current = existing.orElseThrow(() -> failure(PlanArtifactStoreException.Code.NOT_FOUND));
            if (current.revision() != expectedRevision || artifact.revision() != expectedRevision + 1) {
                throw failure(PlanArtifactStoreException.Code.STALE_REVISION);
            }
            if (!current.contentDigest().equals(expectedContentDigest)) {
                throw failure(PlanArtifactStoreException.Code.DIGEST_CONFLICT);
            }
            if (!current.planId().equals(artifact.planId())
                    || !current.createdAt().equals(artifact.createdAt())) {
                throw failure(PlanArtifactStoreException.Code.IDENTITY_MISMATCH);
            }
            if (!PlanLifecyclePolicy.validTransition(current.status(), artifact.status())) {
                throw failure(PlanArtifactStoreException.Code.INVALID_STATE);
            }
        }
        ensureGeneration(artifact);
        publishObserver.generationDurable(artifact);
        return new PreparedArtifact(artifact, generationPath(artifact));
    }

    /** 单次原子 manifest 替换；prepared generation 必须仍是同一个不可变普通文件。 */
    synchronized void commit(PreparedArtifact prepared) {
        Objects.requireNonNull(prepared, "prepared 不能为空");
        requireArtifactOwner(prepared.artifact());
        verifyDirectory();
        verifyGeneration(prepared.artifact(), prepared.generation());
        Path manifestStage = sessionRoot.resolve(".plan-manifest-" + token() + ".tmp");
        try {
            writeForced(manifestStage, encodeManifest(prepared.artifact()));
            atomicMove(manifestStage, manifestPath(), Files.exists(manifestPath(), LinkOption.NOFOLLOW_LINKS));
            forceDirectory(sessionRoot);
        } catch (PlanArtifactStoreException known) {
            throw known;
        } catch (IOException failure) {
            throw failure(PlanArtifactStoreException.Code.IO_FAILURE);
        } finally {
            deleteStage(manifestStage);
        }
    }

    private void ensureGeneration(PlanArtifact artifact) {
        verifyDirectory();
        Path generation = generationPath(artifact);
        if (Files.exists(generation, LinkOption.NOFOLLOW_LINKS)) {
            verifyGeneration(artifact, generation);
            return;
        }
        Path stage = sessionRoot.resolve(".plan-content-" + token() + ".tmp");
        try {
            writeForced(stage, artifact.markdownContent().getBytes(StandardCharsets.UTF_8));
            atomicMove(stage, generation, false);
            forceDirectory(sessionRoot);
        } catch (PlanArtifactStoreException known) {
            throw known;
        } catch (IOException failure) {
            throw failure(PlanArtifactStoreException.Code.IO_FAILURE);
        } finally {
            deleteStage(stage);
        }
        verifyGeneration(artifact, generation);
    }

    private void verifyGeneration(PlanArtifact artifact, Path generation) {
        if (!generation.equals(generationPath(artifact))) {
            throw failure(PlanArtifactStoreException.Code.PATH_REJECTED);
        }
        verifyRegular(generation);
        try {
            long size = Files.size(generation);
            if (size < 1 || size > PlanArtifact.MAX_CONTENT_UTF8_BYTES) {
                throw failure(PlanArtifactStoreException.Code.LIMIT_EXCEEDED);
            }
            BasicFileAttributes before = attributes(generation);
            String content = decodeUtf8(Files.readAllBytes(generation));
            requireUnchanged(generation, before);
            if (!content.equals(artifact.markdownContent())
                    || !PlanArtifact.digest(content).equals(artifact.contentDigest())) {
                throw failure(PlanArtifactStoreException.Code.CORRUPT);
            }
        } catch (PlanArtifactStoreException known) {
            throw known;
        } catch (IOException failure) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    private PlanArtifact requireExact(PlanArtifact artifact) {
        PlanArtifact verified = load(sessionId)
                .orElseThrow(() -> failure(PlanArtifactStoreException.Code.IO_FAILURE));
        if (!verified.equals(artifact)) throw failure(PlanArtifactStoreException.Code.CORRUPT);
        return verified;
    }

    private java.util.Optional<io.github.liumaishenjian.ccjava.domain.PlanVerificationResumeReview>
            decodeVerificationResumeReview(ObjectNode manifest) {
        if (!manifest.has("verificationResumeReview")) return java.util.Optional.empty();
        JsonNode raw = manifest.get("verificationResumeReview");
        if (!(raw instanceof ObjectNode node) || node.size() != 2
                || !node.has("originalPermissionMode") || !node.has("contextPolicy")) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
        try {
            return java.util.Optional.of(new io.github.liumaishenjian.ccjava.domain.PlanVerificationResumeReview(
                    io.github.liumaishenjian.ccjava.domain.PermissionMode.valueOf(
                            requiredText(node, "originalPermissionMode", 32)),
                    io.github.liumaishenjian.ccjava.domain.PlanContextPolicy.valueOf(
                            requiredText(node, "contextPolicy", 32))));
        } catch (IllegalArgumentException invalid) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    private byte[] encodeManifest(PlanArtifact artifact) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", 2);
        node.put("planId", artifact.planId());
        node.put("sessionId", artifact.sessionId().value());
        node.put("revision", artifact.revision());
        node.put("contentFile", generationName(artifact));
        node.put("contentDigest", artifact.contentDigest());
        node.put("status", artifact.status().name());
        node.put("createdAt", artifact.createdAt().toString());
        node.put("updatedAt", artifact.updatedAt().toString());
        artifact.executionBrief().ifPresent(brief -> node.set("executionBrief",
                ExecutionBriefJson.encode(mapper.createObjectNode(), brief)));
        artifact.verificationResumeReview().ifPresent(review -> {
            ObjectNode encoded = node.putObject("verificationResumeReview");
            encoded.put("originalPermissionMode", review.originalPermissionMode().name());
            encoded.put("contextPolicy", review.contextPolicy().name());
        });
        node.set("evidenceLedger", PlanEvidenceLedgerJson.encode(mapper.createObjectNode(), artifact.evidenceLedger()));
        byte[] bytes = mapper.writeValueAsBytes(node);
        if (bytes.length > MAX_MANIFEST_BYTES) throw failure(PlanArtifactStoreException.Code.LIMIT_EXCEEDED);
        return bytes;
    }

    private ObjectNode parseManifest(byte[] bytes) {
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node == null || !node.isObject()
                    || node.size() < FIELDS.size() - 3 || node.size() > FIELDS.size()
                    || node.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))
                    || !node.has("schemaVersion") || !node.has("planId") || !node.has("sessionId")
                    || !node.has("revision") || !node.has("contentFile") || !node.has("contentDigest")
                    || !node.has("status") || !node.has("createdAt") || !node.has("updatedAt")) {
                throw failure(PlanArtifactStoreException.Code.CORRUPT);
            }
            ObjectNode object = (ObjectNode) node;
            if (requiredPositiveLong(object, "schemaVersion") != 2) {
                throw failure(PlanArtifactStoreException.Code.CORRUPT);
            }
            return object;
        } catch (PlanArtifactStoreException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    /**
     * 只做有界、带宽限期的 orphan 清理：不删除当前或历史已提交 generation，避免与先读旧
     * manifest 的并发读者竞争；只删除高于当前 revision（或无 manifest 时任意 revision）的陈旧文件。
     */
    private void cleanupOrphans(Optional<PlanArtifact> current) {
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
        int examined = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionRoot)) {
            for (Path path : entries) {
                if (++examined > MAX_CLEANUP_ENTRIES) break;
                String name = path.getFileName().toString();
                Matcher matcher = GENERATION.matcher(name);
                boolean temporary = name.startsWith(".plan-") && name.endsWith(".tmp");
                if (!temporary && !matcher.matches()) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) continue;
                FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
                if (!modified.toInstant().isBefore(cutoff)) continue;
                if (matcher.matches() && current.isPresent()
                        && Long.parseLong(matcher.group(1)) <= current.orElseThrow().revision()) continue;
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // 清理不是提交条件；权威 manifest 与读路径仍保持失败关闭。
        }
    }

    private static String requiredText(ObjectNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()
                || value.stringValue().length() > max || value.stringValue().indexOf('\0') >= 0) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
        return value.stringValue();
    }

    private static long requiredPositiveLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
        return value.longValue();
    }

    private static PlanStatus enumValue(ObjectNode node, String field) {
        try {
            return PlanStatus.valueOf(requiredText(node, field, 64));
        } catch (IllegalArgumentException invalid) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    private static Instant instant(ObjectNode node, String field) {
        try {
            return Instant.parse(requiredText(node, field, 64));
        } catch (RuntimeException invalid) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    private void verifyDirectory() {
        try {
            if (!Files.isDirectory(sessionRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(sessionRoot)
                    || !sessionRoot.toRealPath().equals(sessionRoot)) {
                throw failure(PlanArtifactStoreException.Code.PATH_REJECTED);
            }
        } catch (PlanArtifactStoreException known) {
            throw known;
        } catch (IOException failure) {
            throw failure(PlanArtifactStoreException.Code.PATH_REJECTED);
        }
    }

    private void verifyRegular(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(PlanArtifactStoreException.Code.PATH_REJECTED);
        }
        try {
            if (!path.toRealPath().getParent().equals(sessionRoot)) {
                throw failure(PlanArtifactStoreException.Code.PATH_REJECTED);
            }
        } catch (IOException failure) {
            throw failure(PlanArtifactStoreException.Code.PATH_REJECTED);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireUnchanged(Path path, BasicFileAttributes before) throws IOException {
        BasicFileAttributes after = attributes(path);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw failure(PlanArtifactStoreException.Code.CORRUPT);
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void atomicMove(Path source, Path target, boolean replace) {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException failure) {
            throw failure(PlanArtifactStoreException.Code.ATOMIC_MOVE_UNAVAILABLE);
        } catch (IOException failure) {
            throw failure(PlanArtifactStoreException.Code.IO_FAILURE);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (java.nio.file.AccessDeniedException unsupportedOnWindows) {
            // Windows 不能稳定打开目录 channel；文件自身已 force 且单个 manifest rename 原子。
        }
    }

    private static void deleteStage(Path stage) {
        try {
            if (Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(stage);
        } catch (IOException ignored) {
        }
    }

    private Path manifestPath() { return sessionRoot.resolve(MANIFEST_FILE); }
    private Path generationPath(PlanArtifact artifact) { return sessionRoot.resolve(generationName(artifact)); }
    private static String generationName(PlanArtifact artifact) {
        return GENERATION_PREFIX + artifact.revision() + "-" + artifact.contentDigest() + GENERATION_SUFFIX;
    }
    private static String token() { return UUID.randomUUID().toString().replace("-", ""); }

    private void requireOwner(SessionId requested) {
        if (!sessionId.equals(Objects.requireNonNull(requested, "requested 不能为空"))) {
            throw failure(PlanArtifactStoreException.Code.IDENTITY_MISMATCH);
        }
    }

    private void requireArtifactOwner(PlanArtifact artifact) {
        if (!sessionId.equals(Objects.requireNonNull(artifact, "artifact 不能为空").sessionId())) {
            throw failure(PlanArtifactStoreException.Code.IDENTITY_MISMATCH);
        }
    }

    private static PlanArtifactStoreException failure(PlanArtifactStoreException.Code code) {
        return new PlanArtifactStoreException(code);
    }

    /** 已经 durable、但尚未由 manifest 发布的不可变 generation。 */
    record PreparedArtifact(PlanArtifact artifact, Path generation) {
        PreparedArtifact {
            Objects.requireNonNull(artifact, "artifact 不能为空");
            Objects.requireNonNull(generation, "generation 不能为空");
        }
    }

    @FunctionalInterface
    interface PublishObserver {
        void generationDurable(PlanArtifact artifact);
    }
}
