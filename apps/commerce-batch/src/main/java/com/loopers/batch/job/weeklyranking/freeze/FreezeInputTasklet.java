package com.loopers.batch.job.weeklyranking.freeze;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParameters;
import com.loopers.domain.ranking.WeeklyPeriod;
import com.loopers.domain.ranking.freeze.WeeklyRankingInputFreezer;
import lombok.RequiredArgsConstructor;
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
public class FreezeInputTasklet implements Tasklet {

    private final WeeklyRankingInputFreezer freezer;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.PERIOD_START + "']}")
    private LocalDate periodStart;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.REVISION + "']}")
    private Long revision;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();
        freezer.freeze(new WeeklyPeriod(periodStart), Math.toIntExact(revision), jobExecutionId);
        return RepeatStatus.FINISHED;
    }
}
