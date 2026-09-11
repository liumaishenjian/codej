package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.PlanArtifactStore;
import io.github.liumaishenjian.ccjava.core.PlanArtifactStoreException;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Plan control Tool 的 CAS 写入接到 FileSessionStore 的 canonical journal 事务。
 *
 * <p>该 Adapter 不允许 Tool 直接调用 FilePlanArtifactStore.save，因为 durable Plan 必须先提交
 * Session journal，再原子发布可重建 manifest。所有路径与 writer/fence 校验仍由 FileSessionStore
 * 和 FilePlanArtifactStore 实施。</p>
 *
 * @since 0.1.0
 */
public final class SessionPlanArtifactStore implements PlanArtifactStore {
    private final FileSessionStore sessions;
    private final SessionId sessionId;

    /** 绑定单个 Session 的 canonical PlanArtifact 写入口。 */
    public SessionPlanArtifactStore(FileSessionStore sessions, SessionId sessionId) {
        this.sessions = Objects.requireNonNull(sessions, "sessions 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
    }

    @Override
    public Optional<PlanArtifact> load(SessionId requested) {
        requireOwner(requested);
        return sessions.planArtifacts(sessionId).load(sessionId);
    }

    @Override
    public PlanArtifact save(PlanArtifact artifact, long expectedRevision, String expectedContentDigest) {
        requireOwner(artifact.sessionId());
        return sessions.savePlanArtifact(artifact, expectedRevision, expectedContentDigest);
    }

    @Override
    public PlanArtifact replaceTerminal(
            PlanArtifact artifact, String expectedPlanId,
            long expectedRevision, String expectedContentDigest) {
        requireOwner(artifact.sessionId());
        return sessions.replaceTerminalPlanArtifact(
                artifact, expectedPlanId, expectedRevision, expectedContentDigest);
    }

    @Override
    public PlanArtifact restoreMissing(PlanArtifact artifact) {
        requireOwner(artifact.sessionId());
        return sessions.planArtifacts(sessionId).restoreMissing(artifact);
    }

    private void requireOwner(SessionId requested) {
        if (!sessionId.equals(Objects.requireNonNull(requested, "sessionId 不能为空"))) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.IDENTITY_MISMATCH);
        }
    }
}
