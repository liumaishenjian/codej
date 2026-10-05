package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/** 只注入已有 PlatformAccess seam 的拒绝条件；真实目录和权限创建不被替换成宽松通道。 */
class PiCredentialSecurityTest {
    @TempDir Path home;
    enum Fault { SYMLINK, REPARSE, HARDLINK, PATH_DRIFT, INODE_DRIFT }

    @ParameterizedTest @EnumSource(Fault.class)
    void referencedSecretSafetyIsEnforcedOnReadWithoutReadingItForMetadata(Fault fault) {
        var identity = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "default");
        var normal = new PiCredentialStore(home);
        PiCredentialStore.Metadata saved;
        try (var value = PiCredentialMaterial.apiKey("synthetic".getBytes(StandardCharsets.US_ASCII))) {
            saved = normal.saveLogin(identity, value, 0, true, CancellationToken.none());
        }
        Path secret = home.resolve(".cc-java/auth/pi/secrets/" + saved.secretRef().orElseThrow() + ".json");
        var platform = new Platform(secret, fault);
        var guarded = new PiCredentialStore(new RestrictedFileSecurity(home, platform), Duration.ofSeconds(1),
                RestrictedFileSecurity.AtomicMover.system(), point -> { });
        assertThat(guarded.snapshot(CancellationToken.none()).find(identity)).contains(saved);
        assertThat(platform.targetBasicReads).isZero();
        assertThatThrownBy(() -> guarded.read(identity, saved.authEpoch(), CancellationToken.none()))
                .hasMessageContaining("AUTH_STORE_INSECURE").hasNoCause();
        assertThatThrownBy(() -> guarded.beginModify(identity, saved.authEpoch(), CancellationToken.none()))
                .hasMessageContaining("AUTH_STORE_INSECURE").hasNoCause();
        try (var transaction = normal.beginModify(identity, saved.authEpoch(), CancellationToken.none())) {
            assertThat(transaction.metadata()).isEqualTo(saved);
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void closeFailureRemainsVisibleWithoutReleasingJvmPermitTwice(boolean transaction) throws Exception {
        // 仅构造私有资源句柄的故障单测；不替代真实文件安全边界，也不声称实测 OS close 失败。
        var channel = new PiFailingCloseChannel();
        var releases = new java.util.concurrent.atomic.AtomicInteger();
        var lock = new java.nio.channels.FileLock(channel, 0, 1, false) {
            public boolean isValid() { return releases.get() == 0; }
            public void release() { releases.incrementAndGet(); }
        };
        var permits = new java.util.concurrent.Semaphore(0);
        Class<?> handleType = Class.forName(PiCredentialStore.class.getName() + "$LockHandle");
        var constructor = handleType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        AutoCloseable handle = (AutoCloseable) constructor.newInstance(channel, lock, permits);
        try (var material = PiCredentialMaterial.apiKey("synthetic".getBytes(StandardCharsets.US_ASCII))) {
            AutoCloseable target = handle;
            if (transaction) {
                var identity = new PiCredentialIdentity("openai", PiCredentialIdentity.AuthMethod.API_KEY, "default");
                var metadata = new PiCredentialStore.Metadata(identity, 1, 1, PiCredentialMaterial.Kind.API_KEY,
                        java.util.Optional.of("0".repeat(32)), java.util.Optional.empty());
                var transactionConstructor = PiCredentialStore.Transaction.class.getDeclaredConstructors()[0];
                transactionConstructor.setAccessible(true);
                target = (AutoCloseable) transactionConstructor.newInstance(new PiCredentialStore(home), handle, metadata, material);
            }
            AutoCloseable closing = target;
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(closing::close).hasMessageContaining("AUTH_STORE_LOCKED").hasNoCause();
                assertThat(permits.availablePermits()).isEqualTo(1);
                assertThat(releases.get()).isEqualTo(1);
                assertThat(channel.closeAttempts).isEqualTo(1);
            }
        }
    }

    /** 故障仅位于 close；所有非关闭 IO 均拒绝，不能充当宽松文件适配器。 */
    private static final class PiFailingCloseChannel extends java.nio.channels.FileChannel {
        private int closeAttempts;
        @Override protected void implCloseChannel() throws IOException { closeAttempts++; throw new IOException("synthetic-private-detail"); }
        @Override public int read(java.nio.ByteBuffer dst) { throw new UnsupportedOperationException(); }
        @Override public long read(java.nio.ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(java.nio.ByteBuffer src) { throw new UnsupportedOperationException(); }
        @Override public long write(java.nio.ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public long position() { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileChannel position(long value) { throw new UnsupportedOperationException(); }
        @Override public long size() { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
        @Override public void force(boolean metadata) { throw new UnsupportedOperationException(); }
        @Override public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target) { throw new UnsupportedOperationException(); }
        @Override public long transferFrom(java.nio.channels.ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
        @Override public int read(java.nio.ByteBuffer dst, long position) { throw new UnsupportedOperationException(); }
        @Override public int write(java.nio.ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
        @Override public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
    }

    private static final class Platform implements RestrictedFileSecurity.PlatformAccess {
        private final Path target;
        private final Fault fault;
        private int targetBasicReads;
        private Platform(Path target, Fault fault) { this.target = target.toAbsolutePath().normalize(); this.fault = fault; }
        private boolean matches(Path path) { return target.equals(path.toAbsolutePath().normalize()); }
        @Override public BasicFileAttributes basic(Path path) throws IOException {
            BasicFileAttributes actual = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!matches(path)) return actual;
            int read = ++targetBasicReads;
            if (fault != Fault.INODE_DRIFT) return actual;
            return new BasicFileAttributes() {
                @Override public FileTime lastModifiedTime() { return actual.lastModifiedTime(); }
                @Override public FileTime lastAccessTime() { return actual.lastAccessTime(); }
                @Override public FileTime creationTime() { return actual.creationTime(); }
                @Override public boolean isRegularFile() { return actual.isRegularFile(); }
                @Override public boolean isDirectory() { return actual.isDirectory(); }
                @Override public boolean isSymbolicLink() { return actual.isSymbolicLink(); }
                @Override public boolean isOther() { return actual.isOther(); }
                @Override public long size() { return actual.size(); }
                @Override public Object fileKey() { return "synthetic-inode-" + read; }
            };
        }
        @Override public boolean symbolicLink(Path path) { return matches(path) && fault == Fault.SYMLINK || Files.isSymbolicLink(path); }
        @Override public boolean reparsePoint(Path path) throws IOException {
            if (matches(path) && fault == Fault.REPARSE) return true;
            try { return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther(); }
            catch (UnsupportedOperationException ignored) { return false; }
        }
        @Override public Path noFollowRealPath(Path path) throws IOException { return path.toRealPath(LinkOption.NOFOLLOW_LINKS); }
        @Override public Path realPath(Path path) throws IOException {
            return matches(path) && fault == Fault.PATH_DRIFT ? path.resolveSibling("synthetic-other") : path.toRealPath();
        }
        @Override public Number linkCount(Path path) throws IOException {
            if (matches(path) && fault == Fault.HARDLINK) return 2L;
            try { return (Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS); }
            catch (UnsupportedOperationException | IllegalArgumentException ignored) { return null; }
        }
        @Override public PosixFileAttributeView posix(Path path) {
            return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public AclFileAttributeView acl(Path path) {
            return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public UserPrincipal lookupPrincipalByName(Path path, String name) throws IOException {
            return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
        }
    }
}
