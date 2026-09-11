package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Optional;

/**
 * 持久化 Session-owned {@link PlanArtifact} 的框架无关端口。
 *
 * <p>保存操作使用 revision 与正文摘要双重 compare-and-set。创建时 expected revision 为 0 且
 * expected digest 为空；更新时两项必须与当前 durable 工件精确匹配。调用方必须持有 Session 的
 * single-writer lease；CAS 防止同一 writer 的迟到 revision，并不替代跨进程 writer fence。实现不得
 * 静默覆盖、回退非原子写或让 Fork 复用原 Session 的可写对象。</p>
 *
 * <p>该端口不定义 Path、JSON、文件锁或目录布局；这些职责属于架构边缘。工件内容也不授予
 * Tool 权限，不替代 Canonical Session journal 或 Recovery Gate。</p>
 *
 * @since 0.1.0
 */
public interface PlanArtifactStore {

    /**
     * 读取指定 Session 当前绑定的工件。
     *
     * @param sessionId Session 身份
     * @return 未创建时为空；损坏或身份不匹配必须抛出类型化失败
     */
    Optional<PlanArtifact> load(SessionId sessionId);

    /**
     * 以 CAS 方式发布一个新 revision；整体可见性必须由单个原子 commit point 决定。
     *
     * @param artifact 要提交的完整工件
     * @param expectedRevision 创建为 0；更新为当前 revision
     * @param expectedContentDigest 创建为空；更新为当前正文摘要
     * @return 可靠提交并重读验证后的工件
     * @throws PlanArtifactStoreException 冲突、损坏、路径或持久化失败
     */
    PlanArtifact save(PlanArtifact artifact, long expectedRevision, String expectedContentDigest);

    /**
     * 在当前工件已经进入不可恢复的终态时，以 CAS 方式开启一个全新 Plan identity。
     *
     * <p>该操作不是普通 revision 更新：新工件必须从 revision 1 的 DRAFT 开始，且不能继承旧计划的
     * Evidence、执行状态或 Task cohort。实现只可替换明确终止且无需恢复的旧工件；任何身份、revision、
     * digest 或状态漂移都必须失败关闭。默认实现拒绝该操作，持久 Adapter 必须显式提供原子语义。</p>
     *
     * @param artifact 全新 identity 的首个 DRAFT 工件
     * @param expectedPlanId 当前终态工件身份
     * @param expectedRevision 当前终态工件 revision
     * @param expectedContentDigest 当前终态工件正文摘要
     * @return 可靠提交并重读验证后的新工件
     */
    default PlanArtifact replaceTerminal(PlanArtifact artifact, String expectedPlanId,
                                         long expectedRevision, String expectedContentDigest) {
        throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.INVALID_STATE);
    }

    /**
     * 仅在本地工件缺失时，根据已经通过 Canonical journal 验证的快照恢复文件。
     *
     * <p>目标存在、损坏或身份冲突都必须失败，不能借恢复覆盖未知字节。</p>
     *
     * @param artifact journal 中的完整工件事实
     * @return 原子 create-only 并重读后的工件
     */
    PlanArtifact restoreMissing(PlanArtifact artifact);
}
