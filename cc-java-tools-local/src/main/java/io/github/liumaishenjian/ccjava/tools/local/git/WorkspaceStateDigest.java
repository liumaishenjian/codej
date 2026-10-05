package io.github.liumaishenjian.ccjava.tools.local.git;

import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 计算批准到执行 Gate 使用的 Git-aware Workspace 状态摘要。
 *
 * <p>Git Workspace 只通过固定只读命令枚举 index 中的 tracked 路径和遵循 standard excludes
 * 的 untracked 路径；摘要同时覆盖 index/porcelain 状态以及非敏感路径当前的普通文件字节或缺失
 * 状态。ignored 构建产物和敏感路径正文不会进入文件读取，而 staged 状态、可访问 tracked 工作树内容
 * 和未忽略的非敏感 untracked 内容都会改变摘要。</p>
 *
 * <p>Git 返回路径仍经过 {@link WorkspaceGuard} 与 realpath containment；读取后重检路径、类型、
 * file key、大小与修改时间，链接逃逸、并发替换、输出/条目/总字节超限均 fail closed。非 Git
 * Workspace 才执行有界全树回退，并排除 {@code .git} 与内部 Session 目录。该应用层校验不是
 * OS Sandbox。</p>
 *
 * @since 0.14.0
 */
public final class WorkspaceStateDigest {
    /** 单次摘要捕获允许枚举的最大路径条目数，超限失败关闭。 */
    public static final int MAX_ENTRIES = 100_000;
    /** 单次摘要累计读取的文件内容字节上限，不是单文件大小豁免。 */
    public static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
    private static final String PROJECT_SESSION_DIRECTORY = ".cc-java/sessions";

    private final WorkspaceGuard guard;
    private final GitReadClient git;
    private final Path internalSessionRoot;
    private final ReadObserver observer;

    /**
     * 创建固定 Workspace 的实时摘要器。
     *
     * @param guard 共享 Workspace 安全边界
     * @param internalSessionRoot 内部 Session Store 根
     */
    public WorkspaceStateDigest(WorkspaceGuard guard, Path internalSessionRoot) {
        this(guard, internalSessionRoot, ignored -> { });
    }

    WorkspaceStateDigest(WorkspaceGuard guard, Path internalSessionRoot, ReadObserver observer) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
        this.git = new GitReadClient(guard.workspace());
        this.internalSessionRoot = Objects.requireNonNull(internalSessionRoot, "internalSessionRoot 不能为空")
                .toAbsolutePath().normalize();
        this.observer = Objects.requireNonNull(observer, "observer 不能为空");
    }

    /**
     * 捕获调用时刻的实时摘要。
     *
     * @return 隐私安全的 SHA-256
     * @throws WorkspaceDigestException 任一安全或预算检查失败时
     */
    public String capture() throws WorkspaceDigestException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (git.requireRepository()) captureRepository(digest); else capturePlain(digest);
            return HexFormat.of().formatHex(digest.digest());
        } catch (GitReadClient.GitReadException failure) {
            if (failure.error().code() == io.github.liumaishenjian.ccjava.domain.ToolErrorCode.NOT_A_GIT_REPOSITORY) {
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    capturePlain(digest);
                    return HexFormat.of().formatHex(digest.digest());
                } catch (IOException | WorkspaceAccessException | NoSuchAlgorithmException fallbackFailure) {
                    throw new WorkspaceDigestException("无法安全计算实时 Workspace 摘要", fallbackFailure);
                }
            }
            throw new WorkspaceDigestException("无法安全计算实时 Workspace 摘要", failure);
        } catch (IOException | WorkspaceAccessException | NoSuchAlgorithmException | ArithmeticException failure) {
            throw new WorkspaceDigestException("无法安全计算实时 Workspace 摘要", failure);
        }
    }

    private void captureRepository(MessageDigest digest)
            throws GitReadClient.GitReadException, IOException, WorkspaceAccessException {
        GitReadClient.WorkspaceDigestInputs inputs = git.workspaceDigestInputs();
        update(digest, "git-v1");
        update(digest, inputs.indexState());
        update(digest, inputs.porcelainState());
        List<String> paths = decodePaths(inputs.paths());
        if (paths.size() > MAX_ENTRIES) throw new IOException("workspace entry limit");
        long total = 0;
        for (String path : paths) total = hashEntry(digest, path, total);
        observer.afterRepositoryHash();
        GitReadClient.WorkspaceDigestInputs rechecked = git.workspaceDigestInputs();
        if (!Arrays.equals(inputs.paths(), rechecked.paths())
                || !Arrays.equals(inputs.indexState(), rechecked.indexState())
                || !Arrays.equals(inputs.porcelainState(), rechecked.porcelainState())) {
            throw new IOException("Git workspace changed while hashing");
        }
    }

    private void capturePlain(MessageDigest digest) throws IOException, WorkspaceAccessException {
        update(digest, "plain-v1");
        Path workspace = guard.workspace();
        ArrayList<String> entries = new ArrayList<>();
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (directory.equals(workspace)) return FileVisitResult.CONTINUE;
                String relative = protocol(workspace.relativize(directory));
                if (relative.equals(".git") || excludedSessionDirectory(directory, relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                validateDuringWalk(relative);
                addBounded(entries, relative);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = protocol(workspace.relativize(file));
                validateDuringWalk(relative);
                addBounded(entries, relative);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw new IOException("workspace entry cannot be inspected", failure);
            }
        });
        entries.sort(Comparator.naturalOrder());
        long total = 0;
        for (String path : entries) total = hashEntry(digest, path, total);
    }

    private boolean excludedSessionDirectory(Path directory, String relative) {
        if (relative.equals(PROJECT_SESSION_DIRECTORY)
                || relative.startsWith(PROJECT_SESSION_DIRECTORY + "/")) return true;
        Path normalized = directory.toAbsolutePath().normalize();
        return normalized.equals(internalSessionRoot) || normalized.startsWith(internalSessionRoot);
    }

    private void validateDuringWalk(String relative) throws IOException {
        try {
            guard.requireExisting(relative);
        } catch (WorkspaceAccessException failure) {
            throw new IOException("unsafe workspace path", failure);
        }
    }

    private long hashEntry(MessageDigest digest, String candidate, long total)
            throws IOException, WorkspaceAccessException {
        String safe;
        try {
            safe = guard.requireSafeGitPath(candidate);
        } catch (WorkspaceAccessException failure) {
            if (failure.error().code()
                    != io.github.liumaishenjian.ccjava.domain.ToolErrorCode.SENSITIVE_PATH) throw failure;
            return hashSensitiveMetadata(digest, candidate, total);
        }
        Path logical = guard.workspace().resolve(platform(safe)).normalize();
        update(digest, "entry");
        update(digest, safe);
        if (!Files.exists(logical, LinkOption.NOFOLLOW_LINKS)) {
            observer.afterRead(safe);
            if (Files.exists(logical, LinkOption.NOFOLLOW_LINKS)) throw new IOException("path appeared");
            update(digest, "missing");
            return total;
        }
        BasicFileAttributes logicalBefore = attributes(logical);
        ValidatedWorkspacePath validated = guard.requireExisting(safe);
        Path real = validated.realPath();
        update(digest, protocol(guard.workspace().relativize(real)));
        if (logicalBefore.isSymbolicLink()) {
            byte[] target = Files.readSymbolicLink(logical).toString().getBytes(StandardCharsets.UTF_8);
            total = addBytes(total, target.length);
            update(digest, "link");
            update(digest, target);
        } else if (Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes before = attributes(real);
            update(digest, "file");
            total = addBytes(total, before.size());
            observer.beforeContentRead(safe);
            try (InputStream input = Files.newInputStream(real)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            observer.afterRead(safe);
            ValidatedWorkspacePath rechecked = guard.requireRegularFile(safe);
            BasicFileAttributes after = attributes(rechecked.realPath());
            if (!rechecked.realPath().equals(real) || changed(before, after)
                    || changed(logicalBefore, attributes(logical))) throw new IOException("file changed");
            return total;
        } else if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "directory");
        } else {
            throw new IOException("unsupported workspace entry");
        }
        observer.afterRead(safe);
        ValidatedWorkspacePath rechecked = guard.requireExisting(safe);
        if (!rechecked.realPath().equals(real) || changed(logicalBefore, attributes(logical))) {
            throw new IOException("path changed");
        }
        return total;
    }

    /**
     * 敏感路径只绑定类型与稳定 metadata，不打开正文；工作树变化仍能触发审批漂移。
     */
    private long hashSensitiveMetadata(MessageDigest digest, String candidate, long total)
            throws IOException, WorkspaceAccessException {
        ValidatedWorkspacePath validated = guard.requireMetadataOnlyForWorkspaceDigest(candidate);
        Path real = validated.realPath();
        BasicFileAttributes before = attributes(real);
        update(digest, "sensitive-entry");
        update(digest, validated.protocolPath());
        update(digest, before.isDirectory() ? "directory" : before.isRegularFile() ? "file"
                : before.isSymbolicLink() ? "link" : "other");
        update(digest, Long.toString(before.size()));
        update(digest, Long.toString(before.lastModifiedTime().toMillis()));
        total = addBytes(total, before.size());
        observer.afterRead(validated.protocolPath());
        ValidatedWorkspacePath rechecked = guard.requireMetadataOnlyForWorkspaceDigest(candidate);
        BasicFileAttributes after = attributes(rechecked.realPath());
        if (!rechecked.realPath().equals(real) || changed(before, after)) throw new IOException("path changed");
        return total;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean changed(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.isDirectory() != after.isDirectory()
                || before.isRegularFile() != after.isRegularFile()
                || before.isSymbolicLink() != after.isSymbolicLink();
    }

    private static long addBytes(long total, long size) throws IOException {
        long updated = Math.addExact(total, size);
        if (updated > MAX_FILE_BYTES) throw new IOException("workspace byte limit");
        return updated;
    }

    private static List<String> decodePaths(byte[] bytes) throws IOException {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != 0) continue;
            if (index > start) paths.add(decodeUtf8(bytes, start, index - start));
            start = index + 1;
        }
        if (start != bytes.length) throw new IOException("Git paths not NUL terminated");
        ArrayList<String> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("Git path is not UTF-8", failure);
        }
    }

    private static void addBounded(List<String> entries, String relative) throws IOException {
        entries.add(relative);
        if (entries.size() > MAX_ENTRIES) throw new IOException("workspace entry limit");
    }

    private static Path platform(String path) {
        return Path.of(path.replace('/', java.io.File.separatorChar));
    }

    private static String protocol(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void update(MessageDigest digest, String value) {
        update(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    @FunctionalInterface
    interface ReadObserver {
        void afterRead(String protocolPath) throws IOException;

        /** 供确定性测试证明敏感路径不会进入正文读取。 */
        default void beforeContentRead(String protocolPath) throws IOException { }

        /** 供确定性测试在文件哈希完成、Git 输入复验前注入竞态。 */
        default void afterRepositoryHash() throws IOException { }
    }

    /** 不携带路径、Git stderr 或文件正文的摘要失败。 */
    public static final class WorkspaceDigestException extends Exception {
        WorkspaceDigestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
