package io.github.liumaishenjian.ccjava.cli.session;

import static org.assertj.core.api.Assertions.*;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** S15/S06：best-effort关闭与资源释放证据分离；不把清空Writer map当成功。 */
class SessionCleanupReceiptTest {
    @TempDir Path root;

    @Test void ordinaryCloseExplicitlyConfirmsOwnedChannels() throws Exception {
        try (var store = store()) {
            store.create(SessionSpec.of("public fixture"));
            assertThat(store.cleanupStatus()).isEqualTo(ResourceCleanupStatus.NOT_STARTED);
            store.close();
            assertThat(store.cleanupStatus()).isEqualTo(ResourceCleanupStatus.RELEASED);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"channel", "lockChannel"})
    void quietlyHandledCloseIoFailureCannotBecomeReleased(String target) throws Exception {
        try (var store = store()) {
            var session = store.create(SessionSpec.of("public fixture"));
            // 仅替换本测试临时Store的channel；先关闭真实句柄，不产生真实泄漏或扩大生产SPI。
            var writers = FileSessionStore.class.getDeclaredField("writerSessions");
            writers.setAccessible(true);
            var opened = ((Map<?, ?>) writers.get(store)).get(session.id());
            var field = opened.getClass().getDeclaredField(target);
            field.setAccessible(true);
            ((FileChannel) field.get(opened)).close();
            field.set(opened, new FailingCloseChannel());
            assertThatCode(store::close).doesNotThrowAnyException();
            assertThat(store.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
            store.close();
            assertThat(store.cleanupStatus()).isEqualTo(ResourceCleanupStatus.UNCONFIRMED);
        }
    }

    private FileSessionStore store() throws IOException {
        var clock = Clock.systemUTC(); var next = new AtomicInteger();
        AgentIdGenerator ids = new AgentIdGenerator() {
            public SessionId newSessionId() { return new SessionId("session-cleanup-" + next.incrementAndGet()); }
            public RunId newRunId() { return new RunId("run-cleanup-" + next.incrementAndGet()); }
        };
        return new FileSessionStore(root.resolve("store"), Files.createDirectory(root.resolve("workspace")),
                ids, new LifecycleDispatcher(clock, AgentEventSink.noop()), clock);
    }

    /** 无实际OS句柄的关闭故障；其他操作被明确拒绝。 */
    private static final class FailingCloseChannel extends FileChannel {
        protected void implCloseChannel() throws IOException { throw new IOException("synthetic private close detail"); }
        public int read(ByteBuffer dst) { throw new UnsupportedOperationException(); }
        public long read(ByteBuffer[] dst, int offset, int length) { throw new UnsupportedOperationException(); }
        public int read(ByteBuffer dst, long position) { throw new UnsupportedOperationException(); }
        public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
        public long write(ByteBuffer[] src, int offset, int length) { throw new UnsupportedOperationException(); }
        public int write(ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
        public long position() { throw new UnsupportedOperationException(); }
        public FileChannel position(long position) { throw new UnsupportedOperationException(); }
        public long size() { throw new UnsupportedOperationException(); }
        public FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
        public void force(boolean metadata) { throw new UnsupportedOperationException(); }
        public long transferTo(long position, long count, WritableByteChannel target) { throw new UnsupportedOperationException(); }
        public long transferFrom(ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
        public MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
        public FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
        public FileLock tryLock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
    }
}
