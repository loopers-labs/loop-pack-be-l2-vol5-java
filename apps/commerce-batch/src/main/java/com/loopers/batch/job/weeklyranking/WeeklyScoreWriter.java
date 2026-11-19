package com.loopers.batch.job.weeklyranking;

import com.loopers.domain.ranking.ProductScore;
import com.loopers.domain.ranking.WeeklyScoreCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class WeeklyScoreWriter implements ItemWriter<ProductScore> {

    private final WeeklyScoreCalculator calculator;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.PERIOD_START + "']}")
    private LocalDate periodStart;

    @Value("#{jobParameters['" + WeeklyRankingJobParameters.REVISION + "']}")
    private Long revision;

    @Override
    public void write(Chunk<? extends ProductScore> scores) {
        String snapshotId = WeeklyRankingSnapshotIds.of(periodStart, revision);
        scores.forEach(score -> calculator.record(snapshotId, score));
    }
}
