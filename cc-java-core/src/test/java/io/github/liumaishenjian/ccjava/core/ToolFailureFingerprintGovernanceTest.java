package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ToolFailureFingerprintGovernanceTest {
    @Test
    void canonicalizesObjectKeysButAllowsChangedArgumentsAndCategories() {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        ToolCall first = call("one", ordered("query", "same", "limit", 5));
        ToolCall reordered = call("two", ordered("limit", 5, "query", "same"));
        governance.record(first, ToolError.of(ToolErrorCode.WEB_SEARCH_FORBIDDEN, "ignored prose"));

        assertThat(governance.repeated(reordered)).isTrue();
        assertThat(governance.repeated(call("three", ordered("query", "changed", "limit", 5)))).isFalse();
        governance.recordSuccess(call("progress", ordered("query", "changed", "limit", 5)), ToolEffect.READ_WORKSPACE);
        assertThat(governance.repeated(reordered)).isFalse();
        assertThat(ToolFailureFingerprintGovernance.repeatedFailure().details().values())
                .containsEntry("requiredStrategyChange", true)
                .containsEntry("retrySameArguments", false)
                .containsEntry("allowedChanges", List.of("arguments", "explanation"));
    }

    @Test
    void unrelatedToolSuccessDoesNotClearFailureButSameToolSuccessDoes() {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        ToolCall forbidden = call("forbidden", ordered("query", "same"));
        governance.record(forbidden, ToolError.of(ToolErrorCode.WEB_SEARCH_FORBIDDEN, "ignored prose"));

        governance.recordSuccess(
                new ToolCall("status", "git_status", JsonObject.empty()), ToolEffect.READ_WORKSPACE);
        assertThat(governance.repeated(call("retry", ordered("query", "same")))).isTrue();
        governance.recordSuccess(
                new ToolCall("patch", "apply_patch", JsonObject.empty()), ToolEffect.WRITE_WORKSPACE);
        assertThat(governance.repeated(call("retry-after-write", ordered("query", "same")))).isTrue();

        governance.recordSuccess(
                call("changed", ordered("query", "different")), ToolEffect.NETWORK_OR_REMOTE);
        assertThat(governance.repeated(call("retry-after-success", ordered("query", "same")))).isFalse();
    }

    @Test
    void sessionTaskMutationReleasesOnlyPlanReadinessFailure() {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        ToolCall review = new ToolCall("review-first", "request_plan_review", JsonObject.empty());
        ToolCall forbidden = call("forbidden", ordered("query", "same"));
        governance.record(review, ToolError.of(ToolErrorCode.PLAN_GATE_BLOCKED, "tasks missing"));
        governance.record(forbidden, ToolError.of(ToolErrorCode.WEB_SEARCH_FORBIDDEN, "forbidden"));

        governance.recordSuccess(new ToolCall("other-session", "other_session_tool", JsonObject.empty()),
                ToolEffect.WRITE_SESSION_STATE);
        assertThat(governance.repeated(new ToolCall(
                "review-still-blocked", "request_plan_review", JsonObject.empty()))).isTrue();

        governance.recordSuccess(new ToolCall("create", "task_create",
                new JsonObject(Map.of("subject", "执行任务"))), ToolEffect.WRITE_SESSION_STATE);

        assertThat(governance.repeated(new ToolCall(
                "review-retry", "request_plan_review", JsonObject.empty()))).isFalse();
        assertThat(governance.repeated(call("web-retry", ordered("query", "same")))).isTrue();
    }

    @Test
    void validationCorrectionRecordIsAtomicAndDoesNotConflateDifferentShapes() throws Exception {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        JsonObject conflict = new JsonObject(Map.of(
                "violation", "mutually_exclusive_fields",
                "fields", List.of("limit", "maxResults")));
        ToolError invalid = ToolError.of(ToolErrorCode.INVALID_ARGUMENTS, "safe validation feedback");
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        ToolCall candidate = call("parallel-" + index,
                                ordered("query", "changed-" + index, "limit", 1, "maxResults", 1));
                        return governance.recordValidationFailureOrRepeated(candidate, invalid, conflict);
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> outcomes = new java.util.ArrayList<>();
            for (var future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(outcomes)
                    .containsExactlyInAnyOrder(false, true, true, true, true, true, true, true);
        }

        assertThat(governance.recordValidationFailureOrRepeated(
                call("different-shape", ordered("query", "mode", "mode", "invalid")), invalid,
                new JsonObject(Map.of("violation", "invalid_mode")))).isFalse();
        assertThat(governance.recordValidationFailureOrRepeated(
                new ToolCall("other", "other_tool", JsonObject.empty()), invalid, conflict)).isFalse();

        ToolCall generic = call("generic-1", ordered("query", "same-generic"));
        assertThat(governance.recordValidationFailureOrRepeated(
                generic, invalid, JsonObject.empty())).isFalse();
        assertThat(governance.recordValidationFailureOrRepeated(
                call("generic-2", ordered("query", "same-generic")), invalid, JsonObject.empty())).isTrue();
    }

    @Test
    void workspaceWriteReleasesOnlyProcessFailureWhosePreconditionsItCanChange() {
        ToolFailureFingerprintGovernance governance = new ToolFailureFingerprintGovernance();
        ToolCall test = new ToolCall("test", "run_command", new JsonObject(Map.of("command", "test")));
        governance.record(test, ToolError.of(ToolErrorCode.PROCESS_EXIT, "test failed"));

        governance.recordSuccess(
                new ToolCall("patch", "apply_patch", JsonObject.empty()), ToolEffect.WRITE_WORKSPACE);

        assertThat(governance.repeated(new ToolCall(
                "retry", "run_command", new JsonObject(Map.of("command", "test"))))).isFalse();
    }

    private static ToolCall call(String id, Map<String, Object> args) {
        return new ToolCall(id, "web_search", new JsonObject(args));
    }
    private static Map<String, Object> ordered(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
