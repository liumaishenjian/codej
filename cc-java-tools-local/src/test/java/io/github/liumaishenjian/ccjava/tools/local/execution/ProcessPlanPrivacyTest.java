package io.github.liumaishenjian.ccjava.tools.local.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessPlanPrivacyTest {

    @Test
    void defaultDiagnosticDoesNotExposeArgvEnvironmentOrStdinValues() {
        String sentinel = "PRIVATE_PROCESS_PLAN_SENTINEL";
        var plan = new AbstractProcessExecutionBackend.Plan(
                List.of("launcher", sentinel),
                Path.of("."),
                Map.of("PRIVATE", sentinel),
                sentinel.getBytes(StandardCharsets.UTF_8));

        assertThat(plan.toString())
                .contains("argumentCount=2", "environmentEntryCount=1")
                .doesNotContain(sentinel, "PRIVATE=");
    }
}
