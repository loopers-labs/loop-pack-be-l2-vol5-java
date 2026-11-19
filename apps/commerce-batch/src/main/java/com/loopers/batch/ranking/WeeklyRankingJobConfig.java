package com.loopers.batch.ranking;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRankingJob";
    private static final int DEFAULT_FETCH_SIZE = 100;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(
        JobRepository jobRepository,
        Step fixPurchaseSnapshotStep,
        Step calculateWeeklyScoresStep,
        Step publishWeeklyRankingStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .start(fixPurchaseSnapshotStep)
            .next(calculateWeeklyScoresStep)
            .next(publishWeeklyRankingStep)
            .build();
    }

    @Bean
    @StepScope
    public FixPurchaseSnapshotTasklet fixPurchaseSnapshotTasklet(
        JdbcTemplate jdbc,
        @Value("#{jobParameters['periodStart']}") String periodStart,
        @Value("#{jobParameters['snapshotId']}") String snapshotId
    ) {
        return new FixPurchaseSnapshotTasklet(jdbc, periodStart, snapshotId);
    }

    @Bean
    public Step fixPurchaseSnapshotStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        FixPurchaseSnapshotTasklet tasklet
    ) {
        return new StepBuilder("fixPurchaseSnapshotStep", jobRepository)
            .tasklet(tasklet, transactionManager)
            .build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<WeeklyScore> weeklyScoreReader(
        DataSource dataSource,
        @Value("#{jobParameters['snapshotId']}") String snapshotId
    ) {
        return new JdbcCursorItemReaderBuilder<WeeklyScore>()
            .name("weeklyScoreReader")
            .saveState(true)
            .dataSource(dataSource)
            .sql("select product_id, sum(quantity) as score "
                + "from weekly_ranking_snapshot_input where snapshot_id = ? "
                + "group by product_id order by product_id")
            .preparedStatementSetter(statement -> statement.setString(1, snapshotId))
            .rowMapper((resultSet, rowNum) -> new WeeklyScore(
                resultSet.getLong("product_id"), resultSet.getLong("score")
            ))
            .fetchSize(DEFAULT_FETCH_SIZE)
            .build();
    }

    @Bean
    @StepScope
    public WeeklyScoreWriter weeklyScoreWriter(
        JdbcTemplate jdbc,
        @Value("#{jobParameters['snapshotId']}") String snapshotId
    ) {
        return new WeeklyScoreWriter(jdbc, snapshotId);
    }

    @Bean
    public Step calculateWeeklyScoresStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        JdbcCursorItemReader<WeeklyScore> weeklyScoreReader,
        WeeklyScoreWriter weeklyScoreWriter,
        @Value("${weekly-ranking.batch.chunk-size:100}") int chunkSize
    ) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("weekly-ranking.batch.chunk-size must be positive");
        }
        return new StepBuilder("calculateWeeklyScoresStep", jobRepository)
            .<WeeklyScore, WeeklyScore>chunk(chunkSize, transactionManager)
            .reader(weeklyScoreReader)
            .processor(item -> item)
            .writer(weeklyScoreWriter)
            .build();
    }

    @Bean
    @StepScope
    public PublishWeeklyRankingTasklet publishWeeklyRankingTasklet(
        JdbcTemplate jdbc,
        @Value("#{jobParameters['periodStart']}") String periodStart,
        @Value("#{jobParameters['snapshotId']}") String snapshotId
    ) {
        return new PublishWeeklyRankingTasklet(jdbc, periodStart, snapshotId);
    }

    @Bean
    public Step publishWeeklyRankingStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        PublishWeeklyRankingTasklet tasklet
    ) {
        return new StepBuilder("publishWeeklyRankingStep", jobRepository)
            .tasklet(tasklet, transactionManager)
            .build();
    }
}
