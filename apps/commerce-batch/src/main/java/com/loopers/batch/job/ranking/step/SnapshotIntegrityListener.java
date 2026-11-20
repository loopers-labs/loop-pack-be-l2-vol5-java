package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.infrastructure.RankingSnapshotStore;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

public class SnapshotIntegrityListener implements StepExecutionListener {
    private final RankingSnapshotStore snapshots;
    private final SnapshotId snapshotId;

    public SnapshotIntegrityListener(RankingSnapshotStore snapshots, SnapshotId snapshotId) {
        this.snapshots = snapshots;
        this.snapshotId = snapshotId;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        var manifest = snapshots.findManifest(snapshotId)
                .orElseThrow(() -> new IllegalStateException("snapshot " + snapshotId + " is not frozen"));
        snapshots.verifyFrozenInput(snapshotId, manifest);
    }
}
