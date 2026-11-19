package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.PublishedRankingStore;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.WeeklySnapshot;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

public class PublishRankingTasklet implements Tasklet {

    private final PublishedRankingStore ranking;

    public PublishRankingTasklet(PublishedRankingStore ranking) {
        this.ranking = ranking;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklySnapshot snapshot = WeeklyRankingParameters.from(
            chunkContext.getStepContext().getStepExecution().getJobParameters()
        ).snapshot();
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
