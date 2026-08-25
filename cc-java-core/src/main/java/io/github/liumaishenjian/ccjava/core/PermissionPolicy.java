package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionRule;
import io.github.liumaishenjian.ccjava.domain.PermissionRuleSource;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S05 类型化 Permission Policy Kernel。
 *
 * <p>显式实现不可被列表顺序改变的优先级：Hard Denial → DENY → PLAN → ASK →
 * ALLOW（含 Session Grant）→ ACCEPT_EDITS/default → Effect default。规则匹配仅使用
 * 可信 selector；Tool 来源和模型参数不能充当绕过凭据。</p>
 *
 * <p>该类型不弹终端审批、不执行 Tool、不持久化 Session，也不是 OS Sandbox。</p>
 *
 * @since 0.5.0
 */
public final class PermissionPolicy implements PermissionGate {

    private final PermissionMode mode;
    private final List<PermissionRule> startupRules;
    private final PermissionSelectorResolver selectors;
    private final HardDenialPolicy hardDenial;
    private final SessionPermissionState sessionState;

    /**
     * 创建固定模式的策略内核。
     *
     * @param mode 当前模式
     * @param startupRules Composition Root 注入的可信启动规则
     * @param selectors Tool-specific selector 提取器
     * @param hardDenial 不可覆盖的保护策略
     * @param sessionState 当前 Session 的内存授权/拒绝状态
     */
    public PermissionPolicy(
            PermissionMode mode,
            List<PermissionRule> startupRules,
            PermissionSelectorResolver selectors,
            HardDenialPolicy hardDenial,
            SessionPermissionState sessionState) {
        this.mode = Objects.requireNonNull(mode, "mode 不能为空");
        this.startupRules = List.copyOf(Objects.requireNonNull(
                startupRules, "startupRules 不能为空"));
        if (this.startupRules.stream().anyMatch(rule ->
                rule.source() != PermissionRuleSource.STARTUP)) {
            throw new IllegalArgumentException("startupRules 只能包含 STARTUP 来源");
        }
        this.selectors = Objects.requireNonNull(selectors, "selectors 不能为空");
        this.hardDenial = Objects.requireNonNull(hardDenial, "hardDenial 不能为空");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState 不能为空");
    }

    @Override
    public PermissionOutcome evaluate(
            ToolInvocation invocation,
            ToolDefinition definition) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(definition, "definition 不能为空");
        PermissionSelector selector = Objects.requireNonNull(
                selectors.resolve(invocation, definition),
                "PermissionSelectorResolver 返回 null");
        if (!selector.toolName().equals(definition.name())
                || selector.source() != definition.source()) {
            throw new IllegalArgumentException("selector Tool 或来源与 Definition 不匹配");
        }
        if (hardDenial.denies(invocation, definition, selector)) {
            return PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.HARD_DENIAL,
                    selector);
        }

        List<PermissionRule> matches = matchingRules(invocation, selector);
        PermissionRule deny = first(matches, PermissionDecision.DENY);
        if (deny != null) {
            return PermissionOutcome.fromRule(deny, PermissionReason.EXPLICIT_DENY, selector);
        }
        if (mode == PermissionMode.PLAN
                && definition.effect() != ToolEffect.READ_WORKSPACE
                && definition.effect() != ToolEffect.NETWORK_OR_REMOTE
                && definition.effect() != ToolEffect.PLAN_ARTIFACT_WRITE
                && definition.effect() != ToolEffect.USER_INTERACTION
                && definition.effect() != ToolEffect.READ_SESSION_STATE
                && definition.effect() != ToolEffect.WRITE_SESSION_STATE) {
            return PermissionOutcome.of(
                    PermissionDecision.DENY,
                    PermissionReason.PLAN_RESTRICTION,
                    selector);
        }
        PermissionRule ask = first(matches, PermissionDecision.ASK);
        if (ask != null) {
            return PermissionOutcome.fromRule(ask, PermissionReason.EXPLICIT_ASK, selector);
        }
        PermissionRule allow = first(matches, PermissionDecision.ALLOW);
        if (allow != null) {
            PermissionReason reason = allow.source() == PermissionRuleSource.SESSION
                    ? PermissionReason.SESSION_GRANT
                    : PermissionReason.EXPLICIT_ALLOW;
            return PermissionOutcome.fromRule(allow, reason, selector);
        }
        if (mode == PermissionMode.ACCEPT_EDITS
                && definition.effect() == ToolEffect.WRITE_WORKSPACE) {
            return PermissionOutcome.of(
                    PermissionDecision.ALLOW,
                    PermissionReason.ACCEPT_EDITS_DEFAULT,
                    selector);
        }
        return effectDefault(definition, selector);
    }

    private List<PermissionRule> matchingRules(
            ToolInvocation invocation,
            PermissionSelector selector) {
        ArrayList<PermissionRule> rules = new ArrayList<>(startupRules);
        rules.addAll(sessionState.rules(invocation.sessionId()));
        return rules.stream().filter(rule -> rule.matches(selector)).toList();
    }

    private static PermissionRule first(
            List<PermissionRule> rules,
            PermissionDecision behavior) {
        return rules.stream()
                .filter(rule -> rule.behavior() == behavior)
                .findFirst()
                .orElse(null);
    }

    private static PermissionOutcome effectDefault(
            ToolDefinition definition,
            PermissionSelector selector) {
        return switch (definition.effect()) {
            case READ_WORKSPACE, READ_SESSION_STATE, WRITE_SESSION_STATE,
                    PLAN_ARTIFACT_WRITE, USER_INTERACTION -> PermissionOutcome.of(
                    PermissionDecision.ALLOW,
                    PermissionReason.EFFECT_DEFAULT,
                    selector);
            case WRITE_WORKSPACE, EXECUTE_PROCESS -> PermissionOutcome.of(
                    PermissionDecision.ASK,
                    PermissionReason.EFFECT_DEFAULT,
                    selector);
            case NETWORK_OR_REMOTE -> {
                boolean trustedExternal = definition.source()
                        == io.github.liumaishenjian.ccjava.domain.ToolSource.MCP
                        || definition.source()
                        == io.github.liumaishenjian.ccjava.domain.ToolSource.PLUGIN;
                boolean controlledBuiltinWebSearch = definition.source()
                        == io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN
                        && "web_search".equals(definition.name());
                boolean ask = trustedExternal || controlledBuiltinWebSearch;
                yield PermissionOutcome.of(
                        ask ? PermissionDecision.ASK : PermissionDecision.DENY,
                        ask ? PermissionReason.EFFECT_DEFAULT : PermissionReason.HARD_DENIAL,
                        selector);
            }
            case SYSTEM_OR_DESTRUCTIVE -> {
                boolean controlledBuiltinDelegate = definition.source()
                        == io.github.liumaishenjian.ccjava.domain.ToolSource.BUILT_IN
                        && "delegate_agent".equals(definition.name());
                yield PermissionOutcome.of(
                        controlledBuiltinDelegate ? PermissionDecision.ASK : PermissionDecision.DENY,
                        controlledBuiltinDelegate ? PermissionReason.EFFECT_DEFAULT : PermissionReason.HARD_DENIAL,
                        selector);
            }
        };
    }
}
