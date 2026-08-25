package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import io.github.liumaishenjian.ccjava.domain.PermissionRuleSource;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {

    private static final SessionId SESSION = new SessionId("session-1");
    private final InMemorySessionPermissionState sessions = new InMemorySessionPermissionState();

    @Test
    void fixedPriorityCannotBeChangedByRuleOrder() {
        PermissionSelector scope = new PermissionSelector("write_file", ToolSource.BUILT_IN, "src/Test.java");
        PermissionRule allow = rule(PermissionDecision.ALLOW, scope);
        PermissionRule ask = rule(PermissionDecision.ASK, scope);
        PermissionRule deny = rule(PermissionDecision.DENY, scope);

        for (List<PermissionRule> order : List.of(
                List.of(allow, ask, deny),
                List.of(deny, allow, ask),
                List.of(ask, deny, allow))) {
            var outcome = policy(PermissionMode.DEFAULT, order, false)
                    .evaluate(invocation("write_file", Map.of("path", "src/Test.java")),
                            definition("write_file", ToolEffect.WRITE_WORKSPACE));
            assertThat(outcome.decision()).isEqualTo(PermissionDecision.DENY);
            assertThat(outcome.reason()).isEqualTo(PermissionReason.EXPLICIT_DENY);
        }
    }

    @Test
    void hardDenialBeatsRulesSessionGrantPlanAndApprovalPath() {
        PermissionSelector scope = new PermissionSelector("write_file", ToolSource.BUILT_IN, ".git/config");
        sessions.grant(SESSION, scope);
        PermissionPolicy policy = policy(
                PermissionMode.ACCEPT_EDITS,
                List.of(rule(PermissionDecision.ALLOW, scope)),
                true);

        var outcome = policy.evaluate(
                invocation("write_file", Map.of("path", ".git/config")),
                definition("write_file", ToolEffect.WRITE_WORKSPACE));

        assertThat(outcome.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(outcome.reason()).isEqualTo(PermissionReason.HARD_DENIAL);
    }

    @Test
    void planBeatsAskAllowAndSessionGrantButStillAllowsReads() {
        PermissionSelector write = new PermissionSelector("write_file", ToolSource.BUILT_IN, "src/Test.java");
        sessions.grant(SESSION, write);
        PermissionPolicy policy = policy(
                PermissionMode.PLAN,
                List.of(rule(PermissionDecision.ASK, write), rule(PermissionDecision.ALLOW, write)),
                false);

        assertThat(policy.evaluate(
                invocation("write_file", Map.of("path", "src/Test.java")),
                definition("write_file", ToolEffect.WRITE_WORKSPACE)).reason())
                .isEqualTo(PermissionReason.PLAN_RESTRICTION);
        assertThat(policy.evaluate(
                invocation("read_file", Map.of()),
                definition("read_file", ToolEffect.READ_WORKSPACE)).decision())
                .isEqualTo(PermissionDecision.ALLOW);
    }

    @Test
    void askBeatsAllowAndSessionGrant() {
        PermissionSelector scope = new PermissionSelector("run_command", ToolSource.BUILT_IN, "./mvnw test");
        sessions.grant(SESSION, scope);
        PermissionPolicy policy = policy(
                PermissionMode.DEFAULT,
                List.of(rule(PermissionDecision.ALLOW, scope), rule(PermissionDecision.ASK, scope)),
                false);

        var outcome = policy.evaluate(
                invocation("run_command", Map.of("command", "./mvnw test")),
                definition("run_command", ToolEffect.EXECUTE_PROCESS));

        assertThat(outcome.decision()).isEqualTo(PermissionDecision.ASK);
        assertThat(outcome.reason()).isEqualTo(PermissionReason.EXPLICIT_ASK);
    }

    @Test
    void acceptEditsOnlyAllowsWorkspaceWriteAndNeverOpaqueProcess() {
        PermissionPolicy policy = policy(PermissionMode.ACCEPT_EDITS, List.of(), false);

        assertThat(policy.evaluate(
                invocation("write_file", Map.of("path", "notes.txt")),
                definition("write_file", ToolEffect.WRITE_WORKSPACE)).decision())
                .isEqualTo(PermissionDecision.ALLOW);
        assertThat(policy.evaluate(
                invocation("run_command", Map.of("command", "printf edit")),
                definition("run_command", ToolEffect.EXECUTE_PROCESS)).decision())
                .isEqualTo(PermissionDecision.ASK);
    }

    @Test
    void sessionGrantMatchesExactToolAndSelectorOnly() {
        PermissionSelector granted = new PermissionSelector("run_command", ToolSource.BUILT_IN, "./mvnw test");
        sessions.grant(SESSION, granted);
        PermissionPolicy policy = policy(PermissionMode.DEFAULT, List.of(), false);

        assertThat(policy.evaluate(
                invocation("run_command", Map.of("command", "./mvnw test")),
                definition("run_command", ToolEffect.EXECUTE_PROCESS)).reason())
                .isEqualTo(PermissionReason.SESSION_GRANT);
        assertThat(policy.evaluate(
                invocation("run_command", Map.of("command", "./mvnw verify")),
                definition("run_command", ToolEffect.EXECUTE_PROCESS)).decision())
                .isEqualTo(PermissionDecision.ASK);
        assertThat(policy.evaluate(
                invocation("other", Map.of()),
                definition("other", ToolEffect.EXECUTE_PROCESS)).decision())
                .isEqualTo(PermissionDecision.ASK);
        var changedSource = policy.evaluate(
                invocation("run_command", Map.of("command", "./mvnw test")),
                definition("run_command", ToolEffect.EXECUTE_PROCESS, ToolSource.MCP));
        assertThat(changedSource.decision()).isEqualTo(PermissionDecision.ASK);
        assertThat(changedSource.reason()).isNotEqualTo(PermissionReason.SESSION_GRANT);
    }

    @Test
    void pluginSessionGrantCannotCrossPluginComponentToolOrSource() {
        String grantedName = "plugin__alpha__tool-provider__remote__search";
        PermissionSelector granted = new PermissionSelector(
                grantedName, ToolSource.PLUGIN, "remote-scope");
        sessions.grant(SESSION, granted);
        PermissionPolicy policy = new PermissionPolicy(
                PermissionMode.DEFAULT, List.of(),
                (invocation, definition) -> new PermissionSelector(
                        definition.name(), definition.source(), "remote-scope"),
                new DefaultHardDenialPolicy(), sessions);

        assertThat(policy.evaluate(invocation(grantedName, Map.of()),
                definition(grantedName, ToolEffect.NETWORK_OR_REMOTE, ToolSource.PLUGIN)).reason())
                .isEqualTo(PermissionReason.SESSION_GRANT);
        for (String other : List.of(
                "plugin__beta__tool-provider__remote__search",
                "plugin__alpha__tool-provider__other__search",
                "plugin__alpha__tool-provider__remote__write")) {
            assertThat(policy.evaluate(invocation(other, Map.of()),
                    definition(other, ToolEffect.NETWORK_OR_REMOTE, ToolSource.PLUGIN)).decision())
                    .isEqualTo(PermissionDecision.ASK);
        }
        assertThat(policy.evaluate(invocation(grantedName, Map.of()),
                definition(grantedName, ToolEffect.NETWORK_OR_REMOTE, ToolSource.MCP)).reason())
                .isNotEqualTo(PermissionReason.SESSION_GRANT);
    }

    @Test
    void sourceAndModelSuppliedPseudoRulesCannotExpandPermission() {
        PermissionPolicy policy = policy(PermissionMode.DEFAULT, List.of(), false);
        ToolDefinition external = definition(
                "remote_mutation", ToolEffect.NETWORK_OR_REMOTE, ToolSource.MCP);
        var outcome = policy.evaluate(
                invocation("remote_mutation", Map.of(
                        "rule", "allow", "source", "startup", "effect", "read_workspace")),
                external);

        assertThat(outcome.decision()).isEqualTo(PermissionDecision.DENY);
        assertThat(outcome.reason()).isEqualTo(PermissionReason.HARD_DENIAL);
    }

    @Test
    void defaultHardDenialAllowsTrustedExternalAndControlledBuiltinSearchToReachApproval() {
        PermissionPolicy policy = new PermissionPolicy(
                PermissionMode.DEFAULT,
                List.of(),
                new DefaultPermissionSelectorResolver(),
                new DefaultHardDenialPolicy(),
                sessions);

        assertThat(policy.evaluate(
                invocation("mcp__server__tool", Map.of()),
                definition("mcp__server__tool", ToolEffect.NETWORK_OR_REMOTE, ToolSource.MCP)).decision())
                .isEqualTo(PermissionDecision.ASK);
        assertThat(policy.evaluate(
                invocation("plugin__alpha__tool-provider__remote__search", Map.of()),
                definition("plugin__alpha__tool-provider__remote__search",
                        ToolEffect.NETWORK_OR_REMOTE, ToolSource.PLUGIN)).decision())
                .isEqualTo(PermissionDecision.ASK);
        assertThat(policy.evaluate(
                invocation("web_search", Map.of("query", "bounded")),
                definition("web_search", ToolEffect.NETWORK_OR_REMOTE, ToolSource.BUILT_IN)).decision())
                .isEqualTo(PermissionDecision.ASK);
        assertThat(policy.evaluate(
                invocation("fake_network", Map.of()),
                definition("fake_network", ToolEffect.NETWORK_OR_REMOTE, ToolSource.BUILT_IN)).decision())
                .isEqualTo(PermissionDecision.DENY);
        assertThat(policy.evaluate(
                invocation("plugin_system", Map.of()),
                definition("plugin_system", ToolEffect.SYSTEM_OR_DESTRUCTIVE, ToolSource.PLUGIN)).decision())
                .isEqualTo(PermissionDecision.DENY);
    }

    @Test
    void batchBTaskSessionEffectsAllowAllModesWithoutOverridingDenyOrHardDenial() {
        for (PermissionMode mode : PermissionMode.values()) {
            PermissionPolicy policy = policy(mode, List.of(), true);
            assertThat(policy.evaluate(invocation("task_list", Map.of()),
                    definition("task_list", ToolEffect.READ_SESSION_STATE)).decision())
                    .as("READ_SESSION_STATE in %s", mode)
                    .isEqualTo(PermissionDecision.ALLOW);
            assertThat(policy.evaluate(invocation("task_update", Map.of()),
                    definition("task_update", ToolEffect.WRITE_SESSION_STATE)).decision())
                    .as("WRITE_SESSION_STATE in %s", mode)
                    .isEqualTo(PermissionDecision.ALLOW);
        }
        PermissionRule deny = rule(PermissionDecision.DENY,
                PermissionSelector.toolWide("task_update", ToolSource.BUILT_IN));
        assertThat(policy(PermissionMode.DEFAULT, List.of(deny), true).evaluate(
                invocation("task_update", Map.of()),
                definition("task_update", ToolEffect.WRITE_SESSION_STATE)).decision())
                .isEqualTo(PermissionDecision.DENY);
        assertThat(policy(PermissionMode.DEFAULT, List.of(), true).evaluate(
                invocation("task_update", Map.of()),
                definition("task_update", ToolEffect.WRITE_SESSION_STATE, ToolSource.PLUGIN)).reason())
                .isEqualTo(PermissionReason.HARD_DENIAL);
        assertThat(policy(PermissionMode.DEFAULT, List.of(), true).evaluate(
                invocation("task_list", Map.of()),
                definition("task_list", ToolEffect.WRITE_SESSION_STATE)).reason())
                .isEqualTo(PermissionReason.HARD_DENIAL);
    }

    @Test
    void exactBuiltinDelegateAsksInDefaultButPlanDenyAndSpoofRemainClosed() {
        ToolDefinition builtin = definition(
                "delegate_agent", ToolEffect.SYSTEM_OR_DESTRUCTIVE, ToolSource.BUILT_IN);
        assertThat(policy(PermissionMode.DEFAULT, List.of(), true)
                .evaluate(invocation("delegate_agent", Map.of()), builtin))
                .satisfies(outcome -> {
                    assertThat(outcome.decision()).isEqualTo(PermissionDecision.ASK);
                    assertThat(outcome.reason()).isEqualTo(PermissionReason.EFFECT_DEFAULT);
                });
        assertThat(policy(PermissionMode.PLAN, List.of(), true)
                .evaluate(invocation("delegate_agent", Map.of()), builtin).decision())
                .isEqualTo(PermissionDecision.DENY);
        PermissionRule deny = rule(PermissionDecision.DENY,
                PermissionSelector.toolWide("delegate_agent", ToolSource.BUILT_IN));
        assertThat(policy(PermissionMode.DEFAULT, List.of(deny), true)
                .evaluate(invocation("delegate_agent", Map.of()), builtin).reason())
                .isEqualTo(PermissionReason.EXPLICIT_DENY);
        assertThat(policy(PermissionMode.DEFAULT, List.of(), true).evaluate(
                invocation("delegate_agent", Map.of()),
                definition("delegate_agent", ToolEffect.SYSTEM_OR_DESTRUCTIVE, ToolSource.PLUGIN)).reason())
                .isEqualTo(PermissionReason.HARD_DENIAL);
        assertThat(policy(PermissionMode.DEFAULT, List.of(), true).evaluate(
                invocation("other_system", Map.of()),
                definition("other_system", ToolEffect.SYSTEM_OR_DESTRUCTIVE, ToolSource.BUILT_IN)).reason())
                .isEqualTo(PermissionReason.HARD_DENIAL);
    }

    private PermissionPolicy policy(
            PermissionMode mode,
            List<PermissionRule> rules,
            boolean denyGit) {
        HardDenialPolicy hard = denyGit
                ? new DefaultHardDenialPolicy()
                : (invocation, definition, selector) ->
                        definition.effect() == ToolEffect.NETWORK_OR_REMOTE
                                || definition.effect() == ToolEffect.SYSTEM_OR_DESTRUCTIVE;
        return new PermissionPolicy(
                mode,
                rules,
                new DefaultPermissionSelectorResolver(),
                hard,
                sessions);
    }

    private static PermissionRule rule(
            PermissionDecision decision,
            PermissionSelector selector) {
        return new PermissionRule(PermissionRuleSource.STARTUP, decision, selector);
    }

    private static ToolInvocation invocation(String tool, Map<String, ?> arguments) {
        return new ToolInvocation(
                SESSION,
                new RunId("run-1"),
                1,
                new ToolCall("call-1", tool, new JsonObject(arguments)));
    }

    private static ToolDefinition definition(String name, ToolEffect effect) {
        return definition(name, effect, ToolSource.BUILT_IN);
    }

    private static ToolDefinition definition(
            String name,
            ToolEffect effect,
            ToolSource source) {
        return new ToolDefinition(
                name,
                "test",
                "{\"type\":\"object\"}",
                effect,
                source,
                false,
                Duration.ofSeconds(1),
                "text/plain",
                1024);
    }
}
