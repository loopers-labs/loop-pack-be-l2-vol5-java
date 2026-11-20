package com.loopers.batch.job.weeklyranking.score;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParameters;
import com.loopers.domain.ranking.WeeklyPeriod;
import com.loopers.domain.ranking.score.ProductScore;
import com.loopers.domain.ranking.score.WeeklyScoreCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class WeeklyScoreProcessor implements ItemProcessor<Long, ProductScore> {

    private final WeeklyScoreCalculator calculator;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.PERIOD_START + "']}")
    private LocalDate periodStart;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.REVISION + "']}")
    private Long revision;

    @Override
    public ProductScore process(Long productId) {
        return calculator.calculate(new WeeklyPeriod(periodStart).snapshotId(Math.toIntExact(revision)), productId);
    }
}
