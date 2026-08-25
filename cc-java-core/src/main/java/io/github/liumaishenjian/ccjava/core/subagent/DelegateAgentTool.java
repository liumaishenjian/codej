package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.*;
import io.github.liumaishenjian.ccjava.domain.*;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.time.Duration;
import java.util.*;

/**
 * 把父模型的委托意图通过普通 Tool Pipeline 交给 {@link AgentSupervisor}。
 *
 * <p>该 Tool 本身不执行子 Tool；前台等待终态，后台只返回 task identity。所有参数严格解析且不会把
 * 子 Prompt 或完整 report 写入审批摘要。</p>
 * @since 0.12.0
 */
public final class DelegateAgentTool implements AgentTool {
    private static final Set<String> FIELDS = Set.of(
            "definition", "prompt", "tools", "background", "worktree",
            "maxModelTurns", "maxToolCalls", "maxInputTokens", "maxOutputCharacters",
            "timeoutSeconds", "taskIds");
    private final AgentSupervisor supervisor;
    private final java.util.function.Supplier<DelegationId> delegationIds;
    private final int provenanceDepth;

    /**
     * 创建父 Session 顶层委托入口；深度由 Host 固定为 1，不属于模型参数。
     *
     * @param supervisor 当前父 Session 唯一的 Supervisor
     */
    public DelegateAgentTool(AgentSupervisor supervisor) {
        this(supervisor, 1, () -> new DelegationId(UUID.randomUUID().toString()));
    }

    /**
     * 创建 child scope 的嵌套委托入口。
     *
     * <p>调用者必须传入由父请求派生的深度，并复用同一 Supervisor；模型参数 schema 不暴露 depth，
     * 因而子模型不能重置或伪造 provenance。该构造器只供 Host composition 使用。</p>
     *
     * @param supervisor 父子共享的 Supervisor、ledger 与 active queue 所有者
     * @param provenanceDepth Host 计算的当前委托深度
     */
    public DelegateAgentTool(AgentSupervisor supervisor, int provenanceDepth) {
        this(supervisor, provenanceDepth, () -> new DelegationId(UUID.randomUUID().toString()));
    }

    DelegateAgentTool(AgentSupervisor supervisor, int provenanceDepth,
            java.util.function.Supplier<DelegationId> delegationIds) {
        this.supervisor = Objects.requireNonNull(supervisor);
        if (provenanceDepth < 1) throw new IllegalArgumentException("Host provenance depth 必须大于 0");
        this.provenanceDepth = provenanceDepth;
        this.delegationIds = Objects.requireNonNull(delegationIds);
    }
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "delegate_agent",
                "Delegate a bounded child agent",
                "{\"type\":\"object\"}",
                ToolEffect.SYSTEM_OR_DESTRUCTIVE,
                ToolSource.BUILT_IN,
                true,
                Duration.ofMinutes(3),
                "text/plain",
                4096);
    }
    @Override public ToolValidationResult validate(JsonObject arguments){
        try{request(arguments);return ToolValidationResult.validResult();}catch(RuntimeException invalid){return ToolValidationResult.invalid("委托参数无效");}
    }
    @Override public ToolExecutionOutcome execute(ToolInvocation invocation){
        ChildTaskRequest request=request(invocation.call().arguments());
        ChildTaskHandle handle=supervisor.submit(request,invocation.cancellationToken());
        ChildTaskReport report;
        if (request.background()) {
            report = handle.inspect();
        } else {
            try {
                report = handle.await(request.requestedBudget().duration().plusSeconds(1));
                if (!report.status().terminal()) {
                    handle.cancel();
                    report = handle.await(Duration.ofSeconds(5));
                    if (!report.status().terminal()) {
                        return ToolExecutionOutcome.failure(
                                ToolError.of(ToolErrorCode.EXECUTION_FAILED,
                                        "前台子任务未在取消期限内收敛"));
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                handle.cancel();
                return ToolExecutionOutcome.failure(
                        ToolError.of(ToolErrorCode.EXECUTION_FAILED, "等待子任务被中断"));
            }
        }
        return ToolExecutionOutcome.success(render(report));
    }
    private ChildTaskRequest request(JsonObject value){
        if (!FIELDS.containsAll(value.values().keySet())) throw new IllegalArgumentException("未知委托字段");
        String definition=required(value,"definition");String prompt=required(value,"prompt");
        boolean background=booleanValue(value,"background",false); boolean worktree=booleanValue(value,"worktree",false);
        int turns=intValue(value,"maxModelTurns",8);int calls=intValue(value,"maxToolCalls",16);
        int seconds=intValue(value,"timeoutSeconds",120);int output=intValue(value,"maxOutputCharacters",4096);
        long tokens=longValue(value,"maxInputTokens",32_000L);
        Set<String> tools=listValue(value,"tools").stream().map(element -> {
            if (!(element instanceof String name) || name.isBlank()) throw new IllegalArgumentException("Tool 名无效");
            return name;
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<io.github.liumaishenjian.ccjava.domain.task.TaskId> taskScope = listValue(value,"taskIds").stream()
                .map(DelegateAgentTool::taskId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ChildTaskRequest(delegationIds.get(),new AgentDefinitionId(definition),prompt,tools,
                new ChildBudget(turns,calls,tokens,output,Duration.ofSeconds(seconds)),background,provenanceDepth,
                worktree,taskScope);
    }
    private static String required(JsonObject value,String field){return value.string(field).filter(v->!v.isBlank()).orElseThrow();}
    private static boolean booleanValue(JsonObject value,String field,boolean fallback){Object v=value.values().get(field);if(v==null)return fallback;if(!(v instanceof Boolean checked))throw new IllegalArgumentException();return checked;}
    private static int intValue(JsonObject value,String field,int fallback){Object v=value.values().get(field);if(v==null)return fallback;if(!(v instanceof Number number)||number.longValue()!=number.doubleValue()||number.longValue()<Integer.MIN_VALUE||number.longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException();return number.intValue();}
    private static long longValue(JsonObject value,String field,long fallback){Object v=value.values().get(field);if(v==null)return fallback;if(!(v instanceof Number number)||number.longValue()!=number.doubleValue())throw new IllegalArgumentException();return number.longValue();}
    private static List<?> listValue(JsonObject value,String field){Object v=value.values().get(field);if(v==null)return List.of();if(!(v instanceof List<?> list))throw new IllegalArgumentException();return list;}
    private static io.github.liumaishenjian.ccjava.domain.task.TaskId taskId(Object value){
        if (!(value instanceof String text) || !text.matches("task-[1-9][0-9]*")) throw new IllegalArgumentException();
        try{return new io.github.liumaishenjian.ccjava.domain.task.TaskId(Long.parseLong(text.substring(5)));}
        catch(NumberFormatException invalid){throw new IllegalArgumentException();}
    }
    private static String render(ChildTaskReport report){return "taskId="+report.taskId().value()+"; status="+report.status().name().toLowerCase(Locale.ROOT)
            +"; failure="+report.failureCode().name().toLowerCase(Locale.ROOT)+"; modelTurns="+report.modelTurns()+"; toolCalls="+report.toolCalls()
            +"; verified="+report.verified()+"; summary="+report.summary()
            +report.worktreeDisposition().map(value -> "; worktree="+value.toLowerCase(Locale.ROOT)).orElse("");}
}
