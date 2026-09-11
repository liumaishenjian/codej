package io.github.liumaishenjian.ccjava.tools.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.tools.local.git.GitReadClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSnapshotTest {

    @TempDir
    Path workspace;

    @Test
    void distinguishesConfirmedNonRepositoryFromUnknownProbeFailure() {
        WorkspaceSnapshot nonRepository = WorkspaceSnapshot.capture(new GitReadClient(workspace));
        WorkspaceSnapshot unknown = WorkspaceSnapshot.capture(
                new GitReadClient(workspace.resolve("missing-workspace")));

        assertThat(nonRepository.repositoryState())
                .isEqualTo(WorkspaceSnapshot.RepositoryState.NON_REPOSITORY);
        assertThat(nonRepository.repository()).isFalse();
        assertThat(nonRepository.branch()).isEqualTo("none");
        assertThat(unknown.repositoryState()).isEqualTo(WorkspaceSnapshot.RepositoryState.UNKNOWN);
        assertThat(unknown.repository()).isFalse();
        assertThat(unknown.branch()).isEqualTo("unknown");
    }
}
