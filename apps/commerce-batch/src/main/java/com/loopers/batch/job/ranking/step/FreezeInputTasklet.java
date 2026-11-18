package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingFreezer;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.loopers.batch.job.ranking.WeeklyRankingTarget;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/** 입력 고정 step. tasklet TX 하나에서 입력 복사 + manifest. 완료한 step 은 재시작 때 다시 실행하지 않는다 (allowStartIfComplete = false). */
public class FreezeInputTasklet implements Tasklet {
    private final WeeklyRankingFreezer freezer;

    public FreezeInputTasklet(WeeklyRankingFreezer freezer) {
        this.freezer = freezer;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution jobExecution = contribution.getStepExecution().getJobExecution();
        WeeklyRankingStore.Snapshot snapshot = freezer.freeze(WeeklyRankingTarget.from(jobExecution.getJobParameters()), jobExecution.getId());
        var context = contribution.getStepExecution().getExecutionContext();
        context.putString("snapshotId", snapshot.snapshotId());
        context.putInt("inputCount", snapshot.inputCount());
        context.putInt("productCount", snapshot.productCount());
        context.putString("inputChecksum", snapshot.inputChecksum());
        return RepeatStatus.FINISHED;
    }
}
