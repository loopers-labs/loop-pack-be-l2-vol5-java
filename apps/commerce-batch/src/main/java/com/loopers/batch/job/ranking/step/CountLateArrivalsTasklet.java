package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklySnapshot;
import com.loopers.batch.job.ranking.store.PublishedRankingStore;
import com.loopers.batch.job.ranking.store.SnapshotStore;

import java.time.LocalDate;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

public class CountLateArrivalsTasklet implements Tasklet {

    private final PublishedRankingStore ranking;
    private final SnapshotStore snapshots;

    public CountLateArrivalsTasklet(PublishedRankingStore ranking, SnapshotStore snapshots) {
        this.ranking = ranking;
        this.snapshots = snapshots;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate periodStart = contribution.getStepExecution().getJobParameters().getLocalDate("periodStart");
        WeeklySnapshot published = ranking.published(periodStart).orElseThrow(() -> new IllegalStateException(
            periodStart + " 주의 공개본이 없어 늦은 구매를 견줄 기준이 없다"
        ));

        long lateArrivals = snapshots.countArrivedAfterFreezing(published);
        contribution.getStepExecution().getExecutionContext().putLong("lateArrivals", lateArrivals);
        if (lateArrivals > 0) {
            throw new IllegalStateException(
                published.id() + " 를 고정한 뒤 그 주의 구매 " + lateArrivals + " 건이 도착했다. 새 revision 이 필요하다"
            );
        }
        return RepeatStatus.FINISHED;
    }
}
