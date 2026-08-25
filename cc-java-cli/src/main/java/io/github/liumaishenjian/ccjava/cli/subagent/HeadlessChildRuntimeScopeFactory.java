package io.github.liumaishenjian.ccjava.cli.subagent;

import io.github.liumaishenjian.ccjava.cli.session.FileCheckpointCoordinator;
import io.github.liumaishenjian.ccjava.cli.session.FileSessionStore;
import io.github.liumaishenjian.ccjava.core.AgentIdGenerator;
import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.AgentSession;
import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ApprovalHandler;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
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
import io.github.liumaishenjian.ccjava.core.hook.HookCoordinator;
import io.github.liumaishenjian.ccjava.core.task.ChildTaskBoardAccess;
import io.github.liumaishenjian.ccjava.core.task.TaskBoardCapabilityFactory;
import io.github.liumaishenjian.ccjava.core.task.TaskCreateTool;
import io.github.liumaishenjian.ccjava.core.task.TaskGetTool;
import io.github.liumaishenjian.ccjava.core.task.TaskListTool;
import io.github.liumaishenjian.ccjava.core.task.TaskUpdateTool;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionContextService;
import io.github.liumaishenjian.ccjava.core.subagent.AgentSupervisor;
import io.github.liumaishenjian.ccjava.core.subagent.ChildRuntimeScope;
import io.github.liumaishenjian.ccjava.core.subagent.ChildRuntimeScopeFactory;
import io.github.liumaishenjian.ccjava.core.subagent.DelegateAgentTool;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendPreference;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionSnapshot;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;
import io.github.liumaishenjian.ccjava.domain.subagent.DelegationId;
import io.github.liumaishenjian.ccjava.domain.worktree.WorktreeDisposition;
import io.github.liumaishenjian.ccjava.domain.worktree.WorktreeLease;
import io.github.liumaishenjian.ccjava.tools.local.LocalWorkspaceBootstrap;
import io.github.liumaishenjian.ccjava.tools.local.execution.ExecutionBackendFactory;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceWriteHardDenial;
import io.github.liumaishenjian.ccjava.tools.local.worktree.LocalGitWorktreeManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 为 Headless 父 Session 重新装配独立的子 Session、Context、Permission 和 Tool Registry。
 *
 * <p>普通 child 使用父 canonical workspace 但重新创建 Workspace Bootstrap，避免 read cache 与 Grant
 * 串扰；worktree child 先通过 fixed-argv Git lease 获得独立 root，再对该 root 完整重装配。每个真实
 * Tool Call 仍进入新 Scope 唯一 {@link ToolExecutionPipeline}，模型循环仍复用 {@link AgentRuntime}。</p>
 *
 * @since 0.12.0
 */
public final class HeadlessChildRuntimeScopeFactory implements ChildRuntimeScopeFactory {
    private final Path parentWorkspace;
    private final Path sessionRoot;
    private final ModelGateway gateway;
    private final ApprovalHandler approvals;
    private final AgentIdGenerator ids;
    private final LifecycleDispatcher lifecycle;
    private final HookCoordinator hooks;
    private final LocalGitWorktreeManager worktrees;
    private final Supplier<AgentSupervisor> supervisor;
    private final ExecutionBackendPreference executionBackend;
    private final ExecutionShell executionShell;
    private final Function<ChildTaskRequest, Optional<ChildTaskBoardAccess>> taskBoardAccess;
    private final ConcurrentMap<DelegationId, WorktreeLease> retainedWorktrees =
            new ConcurrentHashMap<>();

    /**
     * 创建使用父 Workspace、Local backend 和平台 shell 的 child composition。
     *
     * @param parentWorkspace 父 Session 的 canonical Workspace
     * @param sessionRoot 子 Session 持久化根
     * @param gateway 模型回合端口
     * @param approvals 审批端口
     * @param ids Agent 与 Session ID 生成器
     * @param lifecycle 生命周期分发器
     * @param hooks 父 Session Hook 协调器
     */
    public HeadlessChildRuntimeScopeFactory(
            Path parentWorkspace,
            Path sessionRoot,
            ModelGateway gateway,
            ApprovalHandler approvals,
            AgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            HookCoordinator hooks) {
        this(
                parentWorkspace,
                sessionRoot,
                gateway,
                approvals,
                ids,
                lifecycle,
                hooks,
                () -> null,
                ExecutionBackendPreference.LOCAL,
                platformShell());
    }

    /**
     * 创建可复用父 Supervisor 的 child composition；Supplier 用于打破 composition root 的构造环。
     *
     * @param parentWorkspace 父 Session 的 canonical Workspace
     * @param sessionRoot 子 Session 持久化根
     * @param gateway 模型回合端口
     * @param approvals 审批端口
     * @param ids Agent 与 Session ID 生成器
     * @param lifecycle 生命周期分发器
     * @param hooks 父 Session Hook 协调器
     * @param supervisor 延迟取得父 Supervisor 的可信入口
     */
    public HeadlessChildRuntimeScopeFactory(
            Path parentWorkspace,
            Path sessionRoot,
            ModelGateway gateway,
            ApprovalHandler approvals,
            AgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            HookCoordinator hooks,
            Supplier<AgentSupervisor> supervisor) {
        this(
                parentWorkspace,
                sessionRoot,
                gateway,
                approvals,
                ids,
                lifecycle,
                hooks,
                supervisor,
                ExecutionBackendPreference.LOCAL,
                platformShell());
    }

    /**
     * 创建显式继承父 execution 配置的 child composition。
     *
     * @param parentWorkspace 父 Session 的 canonical Workspace
     * @param sessionRoot 子 Session 持久化根
     * @param gateway 模型回合端口
     * @param approvals 审批端口
     * @param ids Agent 与 Session ID 生成器
     * @param lifecycle 生命周期分发器
     * @param hooks 父 Session Hook 协调器
     * @param supervisor 延迟取得父 Supervisor 的可信入口
     * @param executionBackend 父 Session 显式选择的执行后端
     * @param executionShell 父 Session 显式选择的 shell 语义
     */
    public HeadlessChildRuntimeScopeFactory(
            Path parentWorkspace,
            Path sessionRoot,
            ModelGateway gateway,
            ApprovalHandler approvals,
            AgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            HookCoordinator hooks,
            Supplier<AgentSupervisor> supervisor,
            ExecutionBackendPreference executionBackend,
            ExecutionShell executionShell) {
        this(parentWorkspace, sessionRoot, gateway, approvals, ids, lifecycle, hooks, supervisor,
                executionBackend, executionShell, ignored -> Optional.empty());
    }

    /**
     * 创建可由宿主按委托注入 parent Task Board 范围的 child composition。
     *
     * @param parentWorkspace 父 Session 的 canonical Workspace
     * @param sessionRoot 子 Session 持久化根
     * @param gateway 模型回合端口
     * @param approvals 审批端口
     * @param ids Agent 与 Session ID 生成器
     * @param lifecycle 生命周期分发器
     * @param hooks 父 Session Hook 协调器
     * @param supervisor 延迟取得父 Supervisor 的可信入口
     * @param executionBackend 父 Session 显式选择的执行后端
     * @param executionShell 父 Session 显式选择的 shell 语义
     * @param taskBoardAccess 可信宿主按委托返回的 parent Board 固定范围；模型字段不能扩大范围
     */
    public HeadlessChildRuntimeScopeFactory(
            Path parentWorkspace,
            Path sessionRoot,
            ModelGateway gateway,
            ApprovalHandler approvals,
            AgentIdGenerator ids,
            LifecycleDispatcher lifecycle,
            HookCoordinator hooks,
            Supplier<AgentSupervisor> supervisor,
            ExecutionBackendPreference executionBackend,
            ExecutionShell executionShell,
            Function<ChildTaskRequest, Optional<ChildTaskBoardAccess>> taskBoardAccess) {
        this.parentWorkspace = Objects.requireNonNull(parentWorkspace)
                .toAbsolutePath()
                .normalize();
        this.sessionRoot = Objects.requireNonNull(sessionRoot)
                .toAbsolutePath()
                .normalize()
                .resolve("subagents");
        this.gateway = Objects.requireNonNull(gateway);
        this.approvals = Objects.requireNonNull(approvals);
        this.ids = Objects.requireNonNull(ids);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.hooks = Objects.requireNonNull(hooks);
        this.supervisor = Objects.requireNonNull(supervisor);
        this.executionBackend = Objects.requireNonNull(executionBackend);
        this.executionShell = Objects.requireNonNull(executionShell);
        this.taskBoardAccess = Objects.requireNonNull(taskBoardAccess);
        LocalGitWorktreeManager available;
        try {
            available = new LocalGitWorktreeManager(this.parentWorkspace);
        } catch (RuntimeException nonRepositoryOrUnavailableGit) {
            available = null;
        }
        this.worktrees = available;
    }

    @Override
    public ChildRuntimeScope create(
            AgentDefinitionSnapshot definition,
            ChildTaskRequest request,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(request);
        Objects.requireNonNull(cancellationToken);
        WorktreeLease lease = null;
        Path workspace = parentWorkspace;
        AtomicReference<String> disposition = new AtomicReference<>();
        if (request.worktree()) {
            if (worktrees == null) {
                throw new IllegalStateException("当前 Workspace 不是可验证 Git repository");
            }
            String base = git(parentWorkspace, "rev-parse", "HEAD");
            String slug = request.delegationId()
                    .value()
                    .replaceAll("[^a-zA-Z0-9-]", "-")
                    .toLowerCase(Locale.ROOT);
            if (slug.length() > 48) {
                slug = slug.substring(0, 48);
            }
            if (!slug.matches("[a-z0-9].*")) {
                slug = "task-" + slug;
            }
            lease = worktrees.create(slug, base);
            workspace = worktrees.enter(lease);
            disposition.set(lease.disposition().name());
        }
        try {
            LocalWorkspaceBootstrap bootstrap = LocalWorkspaceBootstrap.open(
                    workspace,
                    ExecutionBackendFactory.create(workspace, executionBackend),
                    executionShell);
            Set<String> requested = request.requestedTools().isEmpty()
                    ? definition.visibleTools()
                    : request.requestedTools();
            Set<String> visibleNames = new LinkedHashSet<>(definition.visibleTools());
            visibleNames.retainAll(requested);
            List<AgentTool> availableTools = new ArrayList<>(bootstrap.tools());
            Optional<ChildTaskBoardAccess> parentBoard = Objects.requireNonNull(
                    taskBoardAccess.apply(request), "taskBoardAccess 不能返回 null");
            AtomicReference<io.github.liumaishenjian.ccjava.domain.SessionId> childSessionId =
                    new AtomicReference<>();
            parentBoard.ifPresent(access -> {
                Function<io.github.liumaishenjian.ccjava.core.ToolInvocation,
                        io.github.liumaishenjian.ccjava.domain.task.TaskBoardCapability> capabilities = invocation -> {
                    io.github.liumaishenjian.ccjava.domain.SessionId childId = childSessionId.get();
                    if (childId == null || !childId.equals(invocation.sessionId())) {
                        throw new SecurityException("child Task capability Session 不匹配");
                    }
                    var snapshot = access.board().snapshot();
                    return TaskBoardCapabilityFactory.child(
                            snapshot.boardId(), snapshot.ownerSessionId(), access.actorId(), childId,
                            invocation.runId(), access.taskScope());
                };
                availableTools.add(new TaskCreateTool(access.board(), capabilities));
                availableTools.add(new TaskUpdateTool(
                        access.board(), capabilities, access.actorDirectory()));
                availableTools.add(new TaskListTool(access.board(), capabilities));
                availableTools.add(new TaskGetTool(access.board(), capabilities));
            });
            AgentSupervisor sharedSupervisor = supervisor.get();
            // 嵌套 provenance 只能由 Host 从父请求递增；子模型既看不到也不能覆盖 depth。
            if (sharedSupervisor != null) {
                availableTools.add(new DelegateAgentTool(
                        sharedSupervisor,
                        request.depth() + 1));
            }
            List<AgentTool> visible = availableTools.stream()
                    .filter(tool -> visibleNames.contains(tool.definition().name()))
                    .toList();
            if (visible.size() != visibleNames.size()) {
                throw new IllegalArgumentException("子 Tool scope 含未注册 Tool");
            }
            ToolRegistry registry = new ToolRegistry(visible);
            InMemorySessionPermissionState permissionState =
                    new InMemorySessionPermissionState();
            PermissionPolicy policy = new PermissionPolicy(
                    definition.permissionCeiling(),
                    List.of(),
                    new DefaultPermissionSelectorResolver(),
                    new DefaultHardDenialPolicy(
                            new WorkspaceWriteHardDenial(bootstrap.workspaceGuard())),
                    permissionState);
            FileSessionStore sessions = new FileSessionStore(
                    sessionRoot,
                    workspace,
                    ids,
                    lifecycle,
                    Clock.systemUTC(),
                    HookCoordinator.disabled());
            FileCheckpointCoordinator checkpoints = new FileCheckpointCoordinator(
                    sessionRoot,
                    bootstrap.workspaceGuard(),
                    sessions);
            ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                    registry,
                    policy,
                    approvals,
                    permissionState,
                    lifecycle,
                    sessions,
                    checkpoints,
                    hooks);
            AgentSession child = sessions.create(new SessionSpec(
                    definition.instructions(),
                    Map.of(
                            "model",
                            definition.modelName(),
                            "parentVisibility",
                            "bounded-report",
                            "worktree",
                            Boolean.toString(request.worktree()))));
            childSessionId.set(child.id());
            AgentRuntime runtime = new AgentRuntime(
                    sessions,
                    ids,
                    gateway,
                    new DefaultContextAssembler(),
                    registry,
                    pipeline,
                    lifecycle,
                    sessions,
                    ContextPreparationService.noop(),
                    MemoryContextService.noop(),
                    InstructionContextService.noop(),
                    hooks,
                    io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator.disabled(),
                    io.github.liumaishenjian.ccjava.core.plugin.PluginRunCoordinator.disabled(),
                    io.github.liumaishenjian.ccjava.core.plugin.PluginRunHooks.none());
            WorktreeLease capturedLease = lease;
            return new ChildRuntimeScope(
                    runtime,
                    child.id(),
                    () -> {
                        try {
                            if (!child.isClosed()) {
                                sessions.close(child.id());
                            }
                        } finally {
                            permissionState.clear(child.id());
                            sessions.close();
                            if (capturedLease != null) {
                                worktrees.leave(capturedLease);
                                // 用户价值默认保留；remove 必须由显式 task.remove 动作触发。
                                WorktreeLease result = worktrees.keep(capturedLease);
                                retainedWorktrees.put(request.delegationId(), result);
                                disposition.set(result.disposition().name());
                            }
                        }
                    },
                    () -> Optional.ofNullable(disposition.get()));
        } catch (Exception failure) {
            if (lease != null) {
                try {
                    worktrees.leave(lease);
                } catch (RuntimeException ignored) {
                }
                WorktreeLease result = worktrees.removeClean(lease);
                disposition.set(result.disposition().name());
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("子 Runtime scope 创建失败", failure);
        }
    }

    @Override
    public Optional<String> keepWorktree(DelegationId id) {
        Objects.requireNonNull(id, "delegation id 不能为空");
        WorktreeLease lease = retainedWorktrees.get(id);
        if (lease == null || worktrees == null) {
            return Optional.empty();
        }
        WorktreeLease kept = worktrees.keep(lease);
        retainedWorktrees.put(id, kept);
        return Optional.of(kept.disposition().name());
    }

    @Override
    public Optional<String> removeWorktree(DelegationId id) {
        Objects.requireNonNull(id, "delegation id 不能为空");
        WorktreeLease lease = retainedWorktrees.get(id);
        if (lease == null || worktrees == null) {
            return Optional.empty();
        }
        WorktreeLease result = worktrees.removeClean(lease);
        if (result.disposition() == WorktreeDisposition.REMOVED
                || result.disposition() == WorktreeDisposition.REMOVED_BRANCH_PRESERVED) {
            retainedWorktrees.remove(id, lease);
        } else {
            retainedWorktrees.put(id, result);
        }
        return Optional.of(result.disposition().name());
    }

    private static ExecutionShell platformShell() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win")
                ? ExecutionShell.WINDOWS_PLATFORM
                : ExecutionShell.POSIX_PLATFORM;
    }

    private static String git(Path workspace, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("GIT_TERMINAL_PROMPT", "0");
            environment.put("GCM_INTERACTIVE", "Never");
            Process process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("Git identity 失败");
            }
            String value = new String(
                    process.getInputStream().readNBytes(129),
                    StandardCharsets.UTF_8).trim();
            if (!value.matches("[0-9a-f]{40,64}")) {
                throw new IllegalStateException("Git identity 无效");
            }
            return value;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Git identity 失败", failure);
        }
    }
}
