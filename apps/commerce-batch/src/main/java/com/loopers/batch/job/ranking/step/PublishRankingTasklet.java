package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.WeeklyRankingPublisher;
import com.loopers.batch.job.ranking.WeeklyRankingPublisher.PublishResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/** 공개를 step 의 TX 하나에서 실행한다 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PublishRankingTasklet implements Tasklet {

    private final WeeklyRankingPublisher publisher;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklyRankingParameters params = WeeklyRankingParameters.from(contribution.getStepExecution().getJobParameters());
        PublishResult result = publisher.publish(params);
        log.info("랭킹 공개. snapshotId={} result={}", params.snapshotId(), result);
        return RepeatStatus.FINISHED;
    }
}
