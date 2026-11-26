package com.loopers.batch.job.weeklyranking.publish;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParameters;
import com.loopers.domain.ranking.PublishOutcome;
import com.loopers.domain.ranking.WeeklyPeriod;
import com.loopers.domain.ranking.publish.WeeklyRankingPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class PublishTasklet implements Tasklet {

    /** 계산은 끝났지만 더 새 revision 이 이미 공개돼 있어 pointer 를 바꾸지 않았다. 재시작 대상이 아니다. */
    public static final ExitStatus SUPERSEDED = new ExitStatus("SUPERSEDED");

    private final WeeklyRankingPublisher publisher;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.PERIOD_START + "']}")
    private LocalDate periodStart;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.REVISION + "']}")
    private Long revision;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        PublishOutcome outcome = publisher.publish(new WeeklyPeriod(periodStart), Math.toIntExact(revision));
        if (outcome == PublishOutcome.SUPERSEDED) {
            contribution.setExitStatus(SUPERSEDED);
        }
        return RepeatStatus.FINISHED;
    }
}
