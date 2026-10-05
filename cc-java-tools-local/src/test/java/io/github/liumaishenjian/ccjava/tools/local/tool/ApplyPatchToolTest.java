package io.github.liumaishenjian.ccjava.tools.local.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.text.WorkspaceReadRegistry;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyPatchToolTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @TempDir
    Path workspace;

    @Test
    void patchesCrlfFileWithLfMultilineFragmentAndKeepsCrlfAndBom() throws Exception {
        Path file = write(
                "crlf.java",
                "public class A {\r\n    void run() {\r\n        old();\r\n    }\r\n}\r\n",
                true);
        Fixture fixture = fixture();
        fixture.read("crlf.java");

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "crlf.java",
                "oldText", "    void run() {\n        old();\n    }",
                "newText", "    void run() {\n        first();\n        second();\n    }"));

        assertThat(outcome.successful()).isTrue();
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).startsWith(BOM);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8))
                .isEqualTo("public class A {\r\n    void run() {\r\n        first();\r\n"
                        + "        second();\r\n    }\r\n}\r\n");
    }

    @Test
    void keepsLfFileUnchangedInStyle() throws Exception {
        Path file = write("lf.txt", "head\nold block\ntail\n", false);
        Fixture fixture = fixture();
        fixture.read("lf.txt");

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "lf.txt",
                "oldText", "old block",
                "newText", "new\nblock"));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("head\nnew\nblock\ntail\n");
        assertThat(Files.readAllBytes(file)).doesNotContain((byte) '\r');
    }

    @Test
    void replacesUniqueContextAndPreservesBomAndUnrelatedDirtyContent() throws Exception {
        Path file = write("sample.txt", "user-dirty\r\nold block\r\ntail\r\n", true);
        Fixture fixture = fixture();
        fixture.read("sample.txt");

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "sample.txt",
                "oldText", "old block",
                "newText", "new block"));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readAllBytes(file)).startsWith(BOM);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("user-dirty\r\nnew block\r\ntail");
        assertThat(outcome.content())
                .contains("operation: modified", "- old block", "+ new block");
        assertThat(outcome.metadata().continuation().values())
                .containsEntry("verification", "verified");
    }

    @Test
    void failsClosedOnMixedSeparatorsWhenNewSeparatorsWouldBeSynthesised() throws Exception {
        Path file = write("mixed.txt", "a\r\nb\nc\r\n", false);
        Fixture fixture = fixture();
        fixture.read("mixed.txt");

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "mixed.txt",
                "oldText", "b",
                "newText", "b1\nb2"));

        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.UNSUPPORTED_ENCODING);
        assertThat(Files.readString(file)).isEqualTo("a\r\nb\nc\r\n");
    }

    @Test
    void allowsSingleLineEditInMixedFileWithoutRewritingUnrelatedLines() throws Exception {
        Path file = write("mixed-safe.txt", "a\r\nbee\nc\r\n", false);
        Fixture fixture = fixture();
        fixture.read("mixed-safe.txt");

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "mixed-safe.txt",
                "oldText", "bee",
                "newText", "cee"));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("a\r\ncee\nc\r\n");
    }

    @Test
    void failsClosedOnBareCarriageReturnFileForMultilineEdit() throws Exception {
        Path file = write("bare.txt", "a\rb\rc", false);
        Fixture fixture = fixture();
        fixture.read("bare.txt");

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "bare.txt",
                "oldText", "b",
                "newText", "b\nb2"));

        assertThat(outcome.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.UNSUPPORTED_ENCODING);
        assertThat(Files.readAllBytes(file))
                .isEqualTo("a\rb\rc".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMissingOrAmbiguousContextWithoutChangingFile() throws Exception {
        Path file = write("many.txt", "same\nmiddle\nsame\n", false);
        Fixture fixture = fixture();
        fixture.read("many.txt");

        ToolExecutionOutcome ambiguous = fixture.patch(Map.of(
                "path", "many.txt", "oldText", "same", "newText", "changed"));
        ToolExecutionOutcome missing = fixture.patch(Map.of(
                "path", "many.txt", "oldText", "absent", "newText", "changed"));

        assertThat(ambiguous.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(missing.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(Files.readString(file)).isEqualTo("same\nmiddle\nsame\n");
    }

    @Test
    void replacesAllOnlyWhenExplicitlyRequested() throws Exception {
        Path file = write("all.txt", "same\nsame\n", false);
        Fixture fixture = fixture();
        fixture.read("all.txt");

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "all.txt",
                "oldText", "same",
                "newText", "changed",
                "replaceAll", true));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("changed\nchanged\n");
        assertThat(outcome.content()).contains("replacements: 2");
    }

    @Test
    void keepsWhitespaceAndIndentationExact() throws Exception {
        Path file = write("indent.txt", "if (x) {\n\t\tdeep();\n}\n", false);
        Fixture fixture = fixture();
        fixture.read("indent.txt");

        ToolExecutionOutcome mismatched = fixture.patch(Map.of(
                "path", "indent.txt", "oldText", "    deep();", "newText", "    shallow();"));

        assertThat(mismatched.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(Files.readString(file)).isEqualTo("if (x) {\n\t\tdeep();\n}\n");
    }

    @Test
    void requiresPriorReadEvidenceBeforeModifying() throws Exception {
        Path file = write("gate.txt", "alpha\nbeta\n", false);
        Fixture fixture = fixture();

        ToolExecutionOutcome withoutRead = fixture.patch(Map.of(
                "path", "gate.txt", "oldText", "beta", "newText", "gamma"));
        assertThat(withoutRead.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(withoutRead.error().orElseThrow().message()).contains("read_file");
        assertThat(Files.readString(file)).isEqualTo("alpha\nbeta\n");

        fixture.read("gate.txt");
        ToolExecutionOutcome afterRead = fixture.patch(Map.of(
                "path", "gate.txt", "oldText", "beta", "newText", "gamma"));

        assertThat(afterRead.successful()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("alpha\ngamma\n");
    }

    @Test
    void rejectsPatchWhenPriorReadDoesNotCoverMatchedRegion() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 40; line++) {
            body.append("line-").append(line).append('\n');
        }
        Path file = write("wide.txt", body.toString(), false);
        Fixture fixture = fixture();
        fixture.read("wide.txt", 1, 5);

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "wide.txt", "oldText", "line-30", "newText", "line-30-changed"));

        assertThat(outcome.error().orElseThrow().code()).isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(outcome.error().orElseThrow().message()).contains("覆盖");
        assertThat(Files.readString(file)).isEqualTo(body.toString());
    }

    @Test
    void acceptsPatchWhenPriorReadCoversMatchedRegion() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 40; line++) {
            body.append("line-").append(line).append('\n');
        }
        Path file = write("covered.txt", body.toString(), false);
        Fixture fixture = fixture();
        fixture.read("covered.txt", 28, 6);

        ToolExecutionOutcome outcome = fixture.patch(Map.of(
                "path", "covered.txt", "oldText", "line-30", "newText", "line-30-changed"));

        assertThat(outcome.successful()).isTrue();
        assertThat(Files.readString(file)).contains("line-30-changed");
    }

    @Test
    void rejectsPatchAfterRawConcurrentMutationAndAcceptsAfterReread() throws Exception {
        Path file = write("race.txt", "alpha\nbeta\n", false);
        Fixture fixture = fixture();
        fixture.read("race.txt");
        Files.writeString(file, "alpha\nbeta\nadded-by-user\n");
        Files.setLastModifiedTime(
                file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        ToolExecutionOutcome stale = fixture.patch(Map.of(
                "path", "race.txt", "oldText", "beta", "newText", "gamma"));
        assertThat(stale.error().orElseThrow().code()).isEqualTo(ToolErrorCode.FILE_CONFLICT);
        assertThat(Files.readString(file)).isEqualTo("alpha\nbeta\nadded-by-user\n");

        fixture.read("race.txt");
        ToolExecutionOutcome afterReread = fixture.patch(Map.of(
                "path", "race.txt", "oldText", "beta", "newText", "gamma"));

        assertThat(afterReread.successful()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("alpha\ngamma\nadded-by-user\n");
    }

    @Test
    void allowsChainedEditsAfterOneReadWithoutRereading() throws Exception {
        Path file = write("chain.txt", "one\ntwo\nthree\n", false);
        Fixture fixture = fixture();
        fixture.read("chain.txt");

        ToolExecutionOutcome first = fixture.patch(Map.of(
                "path", "chain.txt", "oldText", "two", "newText", "TWO"));
        ToolExecutionOutcome second = fixture.patch(Map.of(
                "path", "chain.txt", "oldText", "three", "newText", "THREE"));

        assertThat(first.successful()).isTrue();
        assertThat(second.successful()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("one\nTWO\nTHREE\n");
    }

    @Test
    void isolatesPriorReadEvidenceBetweenSessions() throws Exception {
        write("isolated.txt", "alpha\nbeta\n", false);
        Fixture fixture = fixture();
        fixture.read("isolated.txt");

        ToolExecutionOutcome otherSession = fixture.patch(
                Map.of("path", "isolated.txt", "oldText", "beta", "newText", "gamma"),
                CancellationToken.none(),
                "session-2");

        assertThat(otherSession.error().orElseThrow().code())
                .isEqualTo(ToolErrorCode.FILE_CONFLICT);
    }

    @Test
    void refusesSensitiveTraversalOversizeAndCancelledCalls() throws Exception {
        Files.writeString(workspace.resolve(".env"), "secret=x");
        Fixture fixture = fixture();

        assertError(fixture, Map.of(
                "path", ".env", "oldText", "x", "newText", "y"),
                ToolErrorCode.SENSITIVE_PATH, CancellationToken.none());
        assertError(fixture, Map.of(
                "path", "../outside.txt", "oldText", "x", "newText", "y"),
                ToolErrorCode.WORKSPACE_BOUNDARY_VIOLATION, CancellationToken.none());
        assertThat(fixture.patchTool.validate(new JsonObject(Map.of(
                "path", "x",
                "oldText", "a".repeat(512 * 1024 + 1),
                "newText", "b"))).valid()).isFalse();
        Path file = write("cancel.txt", "old", false);
        fixture.read("cancel.txt");
        assertError(fixture, Map.of(
                "path", "cancel.txt", "oldText", "old", "newText", "new"),
                ToolErrorCode.OPERATION_CANCELLED, cancelled());
        assertThat(Files.readString(file)).isEqualTo("old");
    }

    @Test
    void rejectsFragmentsThatDifferOnlyInLineSeparators() throws Exception {
        Fixture fixture = fixture();

        assertThat(fixture.patchTool.validate(new JsonObject(Map.of(
                "path", "x", "oldText", "a\r\nb", "newText", "a\nb"))).valid()).isFalse();
    }

    private void assertError(
            Fixture fixture,
            Map<String, ?> arguments,
            ToolErrorCode expected,
            CancellationToken cancellation) {
        ToolExecutionOutcome outcome = fixture.patch(arguments, cancellation, "session-1");
        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error().orElseThrow().code()).isEqualTo(expected);
    }

    private Fixture fixture() throws Exception {
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        WorkspaceReadRegistry registry = new WorkspaceReadRegistry();
        return new Fixture(
                new ReadFileTool(guard, registry),
                new ApplyPatchTool(guard, registry));
    }

    private Path write(String name, String body, boolean bom) throws Exception {
        Path file = workspace.resolve(name);
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        if (!bom) {
            Files.write(file, content);
            return file;
        }
        byte[] bytes = new byte[content.length + 3];
        System.arraycopy(BOM, 0, bytes, 0, 3);
        System.arraycopy(content, 0, bytes, 3, content.length);
        Files.write(file, bytes);
        return file;
    }

    /** 共享同一 Read 登记表的读取与修改工具组合。 */
    private record Fixture(ReadFileTool readTool, ApplyPatchTool patchTool) {

        private void read(String path) {
            read(path, 1, 500);
        }

        private void read(String path, int startLine, int maxLines) {
            ToolExecutionOutcome outcome = readTool.execute(invocation(
                    "read_file",
                    Map.of("path", path, "startLine", startLine, "maxLines", maxLines),
                    CancellationToken.none(),
                    "session-1"));
            assertThat(outcome.successful()).isTrue();
        }

        private ToolExecutionOutcome patch(Map<String, ?> arguments) {
            return patch(arguments, CancellationToken.none(), "session-1");
        }

        private ToolExecutionOutcome patch(
                Map<String, ?> arguments,
                CancellationToken cancellation,
                String sessionId) {
            return patchTool.execute(
                    invocation("apply_patch", arguments, cancellation, sessionId));
        }

        private static ToolInvocation invocation(
                String name,
                Map<String, ?> arguments,
                CancellationToken cancellation,
                String sessionId) {
            return new ToolInvocation(
                    new SessionId(sessionId),
                    new RunId("run-1"),
                    1,
                    new ToolCall("call-1", name, new JsonObject(arguments)),
                    cancellation);
        }
    }

    private static CancellationToken cancelled() {
        return new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return true;
            }

            @Override
            public Registration onCancellation(Runnable action) {
                action.run();
                return () -> {
                };
            }
        };
    }
}
