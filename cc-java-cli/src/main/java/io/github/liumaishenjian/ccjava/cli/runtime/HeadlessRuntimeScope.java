package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ApprovalHandler;
import io.github.liumaishenjian.ccjava.core.ApprovalReviewGateway;
import io.github.liumaishenjian.ccjava.core.AutoReviewCoordinator;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.DefaultContextAssembler;
import io.github.liumaishenjian.ccjava.core.DefaultHardDenialPolicy;
import io.github.liumaishenjian.ccjava.core.DefaultPermissionSelectorResolver;
import io.github.liumaishenjian.ccjava.core.InMemorySessionPermissionState;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.MemoryContextService;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.PermissionPolicy;
import io.github.liumaishenjian.ccjava.core.ToolExecutionPipeline;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService;
import io.github.liumaishenjian.ccjava.model.springai.ModelGatewayApprovalReviewGateway;
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.cli.session.FileCheckpointCoordinator;
import io.github.liumaishenjian.ccjava.cli.session.FileSessionStore;
import io.github.liumaishenjian.ccjava.domain.PermissionRuleSource;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.settings.RuntimeConfiguration;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceWriteHardDenial;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Run 捕获的完整、不可变运行装配。
 *
 * <p>Scope 只持有 Settings 可替换的模型、可见 builtin Tool、S05 Policy、Pipeline 与 Runtime。
 * Session、durable writer、审批、权限 Grant、Instructions、Memory 和 Lifecycle 由外层 Session 共享；
 * 因此旧 Scope 在已捕获 Run 结束前无需关闭，也不会释放会话级资源。</p>
 *
 * @since 0.8.0
 */
final class HeadlessRuntimeScope {
    private final AgentRuntime runtime;
    private final RuntimeConfiguration configuration;
    private final ToolExecutionPipeline pipeline;
    private final java.util.function.BiFunction<io.github.liumaishenjian.ccjava.core.RunModelBinding,
            ContextPreparationService, HeadlessRuntimeScope> rebinder;

    private HeadlessRuntimeScope(AgentRuntime runtime, RuntimeConfiguration configuration,
                                 ToolExecutionPipeline pipeline,
                                 java.util.function.BiFunction<io.github.liumaishenjian.ccjava.core.RunModelBinding,
                                         ContextPreparationService, HeadlessRuntimeScope> rebinder) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.configuration = Objects.requireNonNull(configuration, "configuration 不能为空");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline 不能为空");
        this.rebinder = Objects.requireNonNull(rebinder, "rebinder 不能为空");
    }

    /**
     * 将已验证的显式模型绑定装配为完整依赖图；同时重建 Runtime、Pipeline 和 reviewer。
     * Session、Tool、权限状态及上下文之外的会话级端口保留原引用，不重新创建 canonical Session。
     * @param binding 本次 Run 冻结的模型与摘要来源
     * @param preparation 共享 Root projection/pending 的本次 Context 视图
     * @return 不依赖同步 facade 的完整新 Scope
     */
    HeadlessRuntimeScope bindModel(io.github.liumaishenjian.ccjava.core.RunModelBinding binding,
                                  ContextPreparationService preparation) {
        return rebinder.apply(Objects.requireNonNull(binding), Objects.requireNonNull(preparation));
    }

    AgentRuntime runtime() {
        return runtime;
    }

    RuntimeConfiguration configuration() {
        return configuration;
    }

    ToolExecutionPipeline pipeline() {
        return pipeline;
    }

    /**
     * 从已验证的 RuntimeConfiguration 构造下一 Run 的完整依赖图。
     *
     * <p>当前 Provider seam 只接受启动时已装配的模型名；后续 Provider adapter 可以在此处
     * 依据非持久化凭证创建候选 Gateway，但不得把凭证放入 RuntimeConfiguration 或诊断。</p>
     */
    static HeadlessRuntimeScope create(
            RuntimeConfiguration configuration,
            String configuredModel,
            ModelGateway gateway,
            ContextPreparationService contextPreparation,
            List<AgentTool> registeredTools,
            FileSessionStore sessions,
            FileCheckpointCoordinator checkpoints,
            LifecycleDispatcher lifecycle,
            AgentIdGenerator ids,
            ApprovalHandler approvals,
            InMemorySessionPermissionState permissionState,
            io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard workspaceGuard,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator skills,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator plugins,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks pluginHooks) {
        return create(configuration, configuredModel, gateway, contextPreparation, registeredTools, sessions,
                checkpoints, lifecycle, ids, approvals, permissionState, workspaceGuard, memoryContext,
                instructionContext, hooks, skills, plugins, pluginHooks,
                io.github.liumaishenjian.ccjava.core.FinalAssistantHandler.acceptAll(), null);
    }

    /**
     * 创建可选受持续规划能力 Gate 约束的 Scope。
     *
     * @param planEligibility 为空保持普通 Runtime；存在时同时过滤 definitions 并在 Pipeline 重检
     */
    static HeadlessRuntimeScope create(
            RuntimeConfiguration configuration,
            String configuredModel,
            ModelGateway gateway,
            ContextPreparationService contextPreparation,
            List<AgentTool> registeredTools,
            FileSessionStore sessions,
            FileCheckpointCoordinator checkpoints,
            LifecycleDispatcher lifecycle,
            AgentIdGenerator ids,
            ApprovalHandler approvals,
            InMemorySessionPermissionState permissionState,
            io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard workspaceGuard,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator skills,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator plugins,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks pluginHooks,
            io.github.liumaishenjian.ccjava.core.PlanEligibilityPolicy planEligibility) {
        return create(configuration, configuredModel, gateway, contextPreparation, registeredTools, sessions,
                checkpoints, lifecycle, ids, approvals, permissionState, workspaceGuard, memoryContext,
                instructionContext, hooks, skills, plugins, pluginHooks,
                io.github.liumaishenjian.ccjava.core.FinalAssistantHandler.acceptAll(), planEligibility);
    }

    /** 创建可在最终 Assistant 线性化点验证结构化终态的 Scope。 */
    static HeadlessRuntimeScope create(
            RuntimeConfiguration configuration,
            String configuredModel,
            ModelGateway gateway,
            ContextPreparationService contextPreparation,
            List<AgentTool> registeredTools,
            FileSessionStore sessions,
            FileCheckpointCoordinator checkpoints,
            LifecycleDispatcher lifecycle,
            AgentIdGenerator ids,
            ApprovalHandler approvals,
            InMemorySessionPermissionState permissionState,
            io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard workspaceGuard,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator skills,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator plugins,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks pluginHooks,
            io.github.liumaishenjian.ccjava.core.FinalAssistantHandler finalAssistantHandler) {
        return create(configuration, configuredModel, gateway, contextPreparation, registeredTools, sessions,
                checkpoints, lifecycle, ids, approvals, permissionState, workspaceGuard, memoryContext,
                instructionContext, hooks, skills, plugins, pluginHooks, finalAssistantHandler, null);
    }

    static HeadlessRuntimeScope create(
            RuntimeConfiguration configuration,
            String configuredModel,
            ModelGateway gateway,
            ContextPreparationService contextPreparation,
            List<AgentTool> registeredTools,
            FileSessionStore sessions,
            FileCheckpointCoordinator checkpoints,
            LifecycleDispatcher lifecycle,
            AgentIdGenerator ids,
            ApprovalHandler approvals,
            InMemorySessionPermissionState permissionState,
            io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard workspaceGuard,
            MemoryContextService memoryContext,
            InstructionContextService instructionContext,
            HookCoordinator hooks,
            io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator skills,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator plugins,
            io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks pluginHooks,
            io.github.liumaishenjian.ccjava.core.FinalAssistantHandler finalAssistantHandler,
            io.github.liumaishenjian.ccjava.core.PlanEligibilityPolicy planEligibility) {
        RuntimeConfiguration checkedConfiguration = Objects.requireNonNull(configuration, "configuration 不能为空");
        if (checkedConfiguration.modelName().isPresent()
                && !checkedConfiguration.modelName().orElseThrow().equals(configuredModel)) {
            throw new IllegalArgumentException("当前 Provider 不支持该模型");
        }
        if (checkedConfiguration.permissionRules().stream()
                .anyMatch(rule -> rule.source() != PermissionRuleSource.STARTUP)) {
            throw new IllegalArgumentException("RuntimeConfiguration 只能包含 STARTUP 规则");
        }
        if (!checkedConfiguration.toolConfigurations().isEmpty()) {
            throw new IllegalArgumentException("当前 builtin Tool 不支持 Runtime 配置");
        }
        List<AgentTool> visibleBuiltins = registeredTools.stream()
                .filter(tool -> tool.definition().source() == ToolSource.BUILT_IN)
                .filter(tool -> checkedConfiguration.enabledBuiltinTools().contains(tool.definition().name()))
                .toList();
        if (visibleBuiltins.size() != checkedConfiguration.enabledBuiltinTools().size()) {
            throw new IllegalArgumentException("RuntimeConfiguration 含未注册 builtin Tool");
        }
        List<AgentTool> visibleTools = java.util.stream.Stream.concat(
                visibleBuiltins.stream(),
                registeredTools.stream().filter(tool -> tool.definition().source() != ToolSource.BUILT_IN))
                .filter(tool -> planEligibility == null || planEligibility.eligible(tool.definition()))
                .toList();
        ToolRegistry registry = new ToolRegistry(visibleTools);
        PermissionPolicy policy = new PermissionPolicy(
                checkedConfiguration.permissionMode(),
                checkedConfiguration.permissionRules(),
                new DefaultPermissionSelectorResolver(),
                new DefaultHardDenialPolicy(new WorkspaceWriteHardDenial(workspaceGuard)),
                permissionState);
        ApprovalReviewGateway reviewGateway = new ModelGatewayApprovalReviewGateway(gateway);
        boolean trustedConfiguredWebSearch = visibleTools.stream()
                .anyMatch(tool -> tool.definition().source() == ToolSource.BUILT_IN
                        && tool.definition().effect() == io.github.liumaishenjian.ccjava.domain.ToolEffect.NETWORK_OR_REMOTE
                        && tool.definition().name().equals("web_search")
                        && checkedConfiguration.enabledBuiltinTools().contains("web_search"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                registry, policy, approvals, permissionState, lifecycle, sessions, checkpoints, hooks, skills,
                checkedConfiguration.approvalReviewer(),
                new AutoReviewCoordinator(reviewGateway, trustedConfiguredWebSearch));
        if (planEligibility != null) pipeline.restrictToPlanning(planEligibility);
        return new HeadlessRuntimeScope(new AgentRuntime(
                sessions, ids, gateway, new DefaultContextAssembler(), registry, pipeline, lifecycle, sessions,
                contextPreparation, memoryContext, instructionContext, hooks, skills, plugins, pluginHooks,
                finalAssistantHandler), checkedConfiguration, pipeline, (binding, preparation) -> {
                    var selectedModel = binding.selection().map(value -> value.modelId());
                    RuntimeConfiguration boundConfiguration = selectedModel.isEmpty() ? checkedConfiguration
                            : new RuntimeConfiguration(selectedModel, checkedConfiguration.permissionMode(),
                                    checkedConfiguration.approvalReviewer(), checkedConfiguration.permissionRules(),
                                    checkedConfiguration.enabledBuiltinTools(), checkedConfiguration.toolConfigurations(),
                                    checkedConfiguration.compactAnchors(), checkedConfiguration.diagnosticsVerbosity());
                    return create(boundConfiguration, selectedModel.orElse(configuredModel), binding.gateway(),
                            preparation, registeredTools, sessions, checkpoints, lifecycle, ids, approvals,
                            permissionState, workspaceGuard, memoryContext, instructionContext, hooks, skills,
                            plugins, pluginHooks, finalAssistantHandler, planEligibility);
                });
    }
}
