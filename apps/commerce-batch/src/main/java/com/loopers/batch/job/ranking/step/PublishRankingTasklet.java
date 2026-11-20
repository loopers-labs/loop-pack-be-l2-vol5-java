package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.SnapshotStatus;
import com.loopers.batch.job.ranking.WeeklyRankingPublisher;
import com.loopers.batch.job.ranking.WeeklyRankingTarget;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/** 공개 step. tasklet TX 하나에서 완성 조건 확인·순위·pointer·상태를 함께 commit 한다. 결과 상태는 step context 의 publishStatus. */
public class PublishRankingTasklet implements Tasklet {
    private final WeeklyRankingPublisher publisher;

    public PublishRankingTasklet(WeeklyRankingPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution jobExecution = contribution.getStepExecution().getJobExecution();
        SnapshotStatus status = publisher.publish(WeeklyRankingTarget.from(jobExecution.getJobParameters()), jobExecution.getId());
        contribution.getStepExecution().getExecutionContext().putString("publishStatus", status.name());
        return RepeatStatus.FINISHED;
    }
}
