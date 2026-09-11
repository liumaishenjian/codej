package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanEvidenceDeclarationToolTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void definitionValidationAndExecutionUseTheSameCompleteTrustedToolSet() {
        SessionId sessionId = new SessionId("session-tool-range");
        RecordingStore store = new RecordingStore(PlanArtifact.create(
                "plan-tool-range", sessionId, "# Plan\n", PlanStatus.DRAFT, NOW));
        LinkedHashSet<String> trusted = new LinkedHashSet<>();
        for (int index = 1; index <= 14; index++) trusted.add("verify_" + index);
        trusted.add("run_command");
        PlanEvidenceDeclarationTool tool = new PlanEvidenceDeclarationTool(
                store, sessionId, CLOCK, trusted);

        assertThat(tool.definition().description())
                .contains("currently registered trusted tools")
                .contains("run_command", "verify_1", "verify_14");
        for (String name : trusted) assertThat(tool.definition().description()).contains(name);

        JsonObject unavailable = verification("weather", "web_search");
        ToolValidationResult validation = tool.validate(unavailable);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.details().values())
                .containsEntry(PlanEvidenceDeclarationTool.FAILURE_REASON_DETAIL,
                        PlanEvidenceDeclarationTool.VERIFICATION_TOOL_UNAVAILABLE);
        assertThat(validation.correctionSignature().values()).isEmpty();

        var outcome = tool.execute(new ToolInvocation(sessionId, new RunId("run-tool-range"), 1,
                new ToolCall("declare-invalid", PlanEvidenceDeclarationTool.NAME, unavailable)));
        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.error()).get().extracting(error -> error.code())
                .isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(outcome.error()).get().extracting(error -> error.details().values())
                .satisfies(details -> assertThat(details).containsEntry(
                        PlanEvidenceDeclarationTool.FAILURE_REASON_DETAIL,
                        PlanEvidenceDeclarationTool.VERIFICATION_TOOL_UNAVAILABLE));
        assertThat(store.saveAttempts).isZero();

        JsonObject available = verification("weather", "run_command");
        assertThat(tool.validate(available).valid()).isTrue();
        assertThat(tool.execute(new ToolInvocation(sessionId, new RunId("run-tool-range"), 2,
                new ToolCall("declare-valid", PlanEvidenceDeclarationTool.NAME, available))).successful())
                .isTrue();
        assertThat(store.saveAttempts).isOne();
    }

    @Test
    void emptyTrustedSetIsAdvertisedWithoutInventingAnAlternative() {
        SessionId sessionId = new SessionId("session-empty-tools");
        RecordingStore store = new RecordingStore(PlanArtifact.create(
                "plan-empty-tools", sessionId, "# Plan\n", PlanStatus.DRAFT, NOW));
        PlanEvidenceDeclarationTool tool = new PlanEvidenceDeclarationTool(
                store, sessionId, CLOCK, Set.of());

        assertThat(tool.definition().description()).endsWith("none.");
        assertThat(tool.validate(verification("weather", "run_command")).details().values())
                .containsEntry(PlanEvidenceDeclarationTool.FAILURE_REASON_DETAIL,
                        PlanEvidenceDeclarationTool.VERIFICATION_TOOL_UNAVAILABLE);
    }

    private static JsonObject verification(String requirementId, String locator) {
        return new JsonObject(Map.of(
                "requirementId", requirementId,
                "kind", "VERIFICATION",
                "locator", locator,
                "label", "weather verification",
                "required", true));
    }

    private static final class RecordingStore implements PlanArtifactStore {
        private PlanArtifact current;
        private int saveAttempts;

        private RecordingStore(PlanArtifact current) {
            this.current = current;
        }

        @Override
        public Optional<PlanArtifact> load(SessionId sessionId) {
            return current.sessionId().equals(sessionId) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public PlanArtifact save(PlanArtifact artifact, long expectedRevision, String expectedContentDigest) {
            saveAttempts++;
            current = artifact;
            return artifact;
        }

        @Override
        public PlanArtifact restoreMissing(PlanArtifact artifact) {
            if (current != null) throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.STALE_REVISION);
            current = artifact;
            return artifact;
        }
    }
}
