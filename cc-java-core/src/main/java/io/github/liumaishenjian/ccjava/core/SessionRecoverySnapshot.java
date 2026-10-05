package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.AgentMessage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord;

/**
 * 文件 Adapter 完整校验 journal 后交给 Core 的框架无关恢复快照。
 *
 * <p>快照只包含可安全进入下一模型回合的规范消息。活动 Run、未完成 Tool 和损坏警告由打开
 * 结果单独表达，不能通过伪造规范 Assistant/Tool Result 消息隐藏。</p>
 *
 * @param sessionId 被恢复的 Session ID
 * @param spec Session 创建配置
 * @param messages 已验证的完整规范消息链
 * @param runIds 历史 Run ID，用于阻止恢复后重复
 * @param parentSessionId Fork 来源
 * @param issues 阻止无条件继续写入的安全恢复问题
 * @param skillRecords 历史成功激活的 privacy-safe Skill 身份；只用于验证，不自动重放
 * @param plan 旧 PlanDocument 的兼容恢复投影
 * @param planArtifact 最新完整 Markdown 工件 revision；缺失时为空
 * @since 0.6.0
 */
public record SessionRecoverySnapshot(
        SessionId sessionId,
        SessionSpec spec,
        List<AgentMessage> messages,
        List<RunId> runIds,
        Optional<SessionId> parentSessionId,
        List<SessionRecoveryIssue> issues,
        List<SkillRecoveryRecord> skillRecords,
        Optional<PlanRecoveryProjection> plan,
        Optional<PlanArtifact> planArtifact) {

    /**
     * 防御性复制恢复数据。
     */
    public SessionRecoverySnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        spec = Objects.requireNonNull(spec, "spec 不能为空");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        runIds = List.copyOf(Objects.requireNonNull(runIds, "runIds 不能为空"));
        parentSessionId = Objects.requireNonNull(parentSessionId, "parentSessionId 不能为空");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues 不能为空"));
        skillRecords = List.copyOf(Objects.requireNonNull(skillRecords, "skillRecords 不能为空"));
        plan = Objects.requireNonNull(plan, "plan 不能为空");
        planArtifact = Objects.requireNonNull(planArtifact, "planArtifact 不能为空");
        if (planArtifact.isPresent()
                && !planArtifact.orElseThrow().sessionId().equals(sessionId)) {
            throw new IllegalArgumentException("PlanArtifact Session ID 不匹配");
        }
        if (plan.isPresent() && planArtifact.isPresent()) {
            PlanRecoveryProjection projection = plan.orElseThrow();
            PlanArtifact artifact = planArtifact.orElseThrow();
            if (!projection.document().id().equals(artifact.planId())) {
                throw new IllegalArgumentException("Plan 与 PlanArtifact ID 不匹配");
            }
            if (projection.document().status() != artifact.status()) {
                throw new IllegalArgumentException("Plan 与 PlanArtifact 状态不匹配");
            }
        }
    }

    /**
     * 兼容现有恢复调用，默认没有持久 Plan。
     * @param sessionId 被恢复的 Session 身份
     * @param spec Session 创建配置
     * @param messages 已验证的完整规范消息链
     * @param runIds 历史 Run 身份
     * @param parentSessionId 可选 Fork 来源
     * @param issues 必须由恢复 Gate 处理的安全问题
     * @param skillRecords 已验证的 Skill 恢复记录
     */
    public SessionRecoverySnapshot(SessionId sessionId, SessionSpec spec, List<AgentMessage> messages,
            List<RunId> runIds, Optional<SessionId> parentSessionId, List<SessionRecoveryIssue> issues,
            List<SkillRecoveryRecord> skillRecords) {
        this(sessionId, spec, messages, runIds, parentSessionId, issues, skillRecords,
                Optional.empty(), Optional.empty());
    }

    /**
     * 兼容已有持久 PlanDocument、但尚无 PlanArtifact 的恢复调用。
     * @param sessionId 被恢复的 Session 身份
     * @param spec Session 创建配置
     * @param messages 已验证的完整规范消息链
     * @param runIds 历史 Run 身份
     * @param parentSessionId 可选 Fork 来源
     * @param issues 必须由恢复 Gate 处理的安全问题
     * @param skillRecords 已验证的 Skill 恢复记录
     * @param plan 可选的旧结构化计划投影，不自动重放执行
     */
    public SessionRecoverySnapshot(SessionId sessionId, SessionSpec spec, List<AgentMessage> messages,
            List<RunId> runIds, Optional<SessionId> parentSessionId, List<SessionRecoveryIssue> issues,
            List<SkillRecoveryRecord> skillRecords, Optional<PlanRecoveryProjection> plan) {
        this(sessionId, spec, messages, runIds, parentSessionId, issues, skillRecords, plan, Optional.empty());
    }

    /**
     * 兼容不含 S11 Skill 记录的既有构造路径。
     *
     * @param sessionId 被恢复的 Session ID
     * @param spec Session 创建配置
     * @param messages 已验证的完整规范消息链
     * @param runIds 历史 Run ID
     * @param parentSessionId Fork 来源
     * @param issues 安全恢复问题
     */
    public SessionRecoverySnapshot(SessionId sessionId, SessionSpec spec, List<AgentMessage> messages,
            List<RunId> runIds, Optional<SessionId> parentSessionId, List<SessionRecoveryIssue> issues) {
        this(sessionId, spec, messages, runIds, parentSessionId, issues, List.of(),
                Optional.empty(), Optional.empty());
    }
}
