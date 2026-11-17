package com.loopers.batch.job.ranking;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration
public class WeeklyRankingJobConfig {
    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeInput";
    public static final String CALCULATE_STEP = "calculateWeeklyScores";
    public static final String READER = "weeklyRankingProduct";

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(JobRepository jobRepository, Step freezeInputStep, Step calculateWeeklyScoresStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .start(freezeInputStep)
            .next(calculateWeeklyScoresStep)
            .build();
    }

    @Bean
    public Step freezeInputStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet((contribution, chunkContext) -> RepeatStatus.FINISHED, transactionManager)
            .build();
    }

    @Bean
    public Step calculateWeeklyScoresStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
        JdbcCursorItemReader<Long> weeklyRankingProductReader, ItemProcessor<Long, ProductScore> weeklyScoreProcessor,
        ItemWriter<ProductScore> weeklyScoreWriter, @Value("${commerce.ranking.chunk-size}") int chunkSize) {
        return new StepBuilder(CALCULATE_STEP, jobRepository)
            .<Long, ProductScore>chunk(chunkSize, transactionManager)
            .reader(weeklyRankingProductReader)
            .processor(weeklyScoreProcessor)
            .writer(weeklyScoreWriter)
            .build();
    }

    @StepScope
    @Bean
    public JdbcCursorItemReader<Long> weeklyRankingProductReader(DataSource dataSource,
        @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return new JdbcCursorItemReaderBuilder<Long>()
            .name(READER)
            .dataSource(dataSource)
            .sql("select distinct product_id from ranking_snapshot_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(statement -> statement.setString(1, snapshotId))
            .rowMapper((resultSet, rowNumber) -> resultSet.getLong(1))
            .saveState(true)
            .build();
    }

    @StepScope
    @Bean
    public ItemProcessor<Long, ProductScore> weeklyScoreProcessor(@Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return productId -> new ProductScore(snapshotId, productId, 0);
    }

    @Bean
    public ItemWriter<ProductScore> weeklyScoreWriter(WeeklyRankingRepository weeklyRankingRepository) {
        return chunk -> { };
    }

    @Bean
    public WeeklyRankingRepository weeklyRankingRepository(JdbcTemplate jdbcTemplate) {
        return new WeeklyRankingRepository(jdbcTemplate);
    }

    @Bean
    public Clock weeklyRankingClock() {
        return Clock.systemUTC();
    }
}
