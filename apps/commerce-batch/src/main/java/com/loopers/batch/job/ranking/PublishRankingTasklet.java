package com.loopers.batch.job.ranking;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

class PublishRankingTasklet implements Tasklet {

    private final PublishedRankingStore ranking;

    PublishRankingTasklet(PublishedRankingStore ranking) {
        this.ranking = ranking;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklySnapshot snapshot = SnapshotParameters.snapshot(
            chunkContext.getStepContext().getStepExecution().getJobParameters()
        );
        requireEveryProductScored(snapshot.id());

        if (!ranking.isRanked(snapshot.id())) {
            ranking.rank(snapshot.id());
        }
        ranking.pointToUnlessNewerPublished(snapshot);
        contribution.getStepExecution().getExecutionContext()
            .putString("resultChecksum", ranking.resultChecksum(snapshot.id()));
        return RepeatStatus.FINISHED;
    }

    private void requireEveryProductScored(String snapshotId) {
        long products = ranking.productCount(snapshotId);
        long scores = ranking.scoreCount(snapshotId);
        if (scores != products) {
            throw new IllegalStateException(
                "snapshot " + snapshotId + " has " + scores + " scores for " + products + " products"
            );
        }
    }
}
