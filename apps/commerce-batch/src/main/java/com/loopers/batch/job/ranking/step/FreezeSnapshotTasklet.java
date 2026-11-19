package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.SnapshotStore;
import com.loopers.batch.job.ranking.WeeklySnapshot;

import java.util.Optional;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

public class FreezeSnapshotTasklet implements Tasklet {

    private final SnapshotStore snapshots;

    public FreezeSnapshotTasklet(SnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklyRankingParameters params = WeeklyRankingParameters.from(
            chunkContext.getStepContext().getStepExecution().getJobParameters()
        );
        WeeklySnapshot snapshot = params.snapshot();
        snapshot.requireId(params.snapshotId());

        Optional<WeeklySnapshot> existing = snapshots.find(snapshot.id());
        if (existing.isPresent()) {
            snapshot.requireSameAs(existing.get());
            return RepeatStatus.FINISHED;
        }
        snapshots.latestRevision(snapshot.periodStart()).ifPresent(snapshot::requireAbove);

        int inputCount = snapshots.copyInput(snapshot);
        snapshots.markFrozen(snapshot, inputCount, snapshots.inputChecksum(snapshot.id()));
        return RepeatStatus.FINISHED;
    }
}
