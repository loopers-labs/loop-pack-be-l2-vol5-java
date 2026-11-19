package com.loopers.batch.job.ranking;

import java.util.Optional;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

class FreezeSnapshotTasklet implements Tasklet {

    private final SnapshotStore snapshots;

    FreezeSnapshotTasklet(SnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobParameters params = chunkContext.getStepContext().getStepExecution().getJobParameters();
        WeeklySnapshot snapshot = SnapshotParameters.snapshot(params);
        snapshot.requireId(SnapshotParameters.requestedId(params));

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
