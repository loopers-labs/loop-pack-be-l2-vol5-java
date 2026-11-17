package com.loopers.batch.job.weeklyranking;

import com.loopers.application.ranking.RankingInputService;
import com.loopers.application.ranking.RankingPublicationService;
import com.loopers.batch.job.weeklyranking.step.FreezeWeeklyRankingInputTasklet;
import com.loopers.batch.job.weeklyranking.step.PublishWeeklyRankingTasklet;
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingScoreProcessor;
import com.loopers.batch.job.weeklyranking.step.WeeklyRankingScoreWriter;
import com.loopers.domain.ranking.RankingProductScore;
import com.loopers.domain.ranking.RankingScoreRepository;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
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
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * spring.batch.job.name=weeklyRanking 일 때만 등록한다(랭킹 설계 4.11).
 * 모든 Step 은 기존 영속 JobRepository 와 단일 transactionManager 를 쓴다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyRankingInput";
    public static final String CALCULATE_STEP = "calculateWeeklyScores";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";
    public static final String PRODUCT_READER = "weeklyRankingProductReader";
    private static final int CHUNK_SIZE = 2;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(
        JobRepository jobRepository, Step freezeWeeklyRankingInput, Step calculateWeeklyScores, Step publishWeeklyRanking
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(WeeklyRankingParameters.VALIDATOR)
            .start(freezeWeeklyRankingInput)
            .next(calculateWeeklyScores)
            .next(publishWeeklyRanking)
            .build();
    }

    @Bean(FREEZE_STEP)
    public Step freezeWeeklyRankingInput(
        JobRepository jobRepository, PlatformTransactionManager transactionManager, RankingInputService inputService
    ) {
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(new FreezeWeeklyRankingInputTasklet(inputService), transactionManager)
            .build();
    }

    @Bean(CALCULATE_STEP)
    public Step calculateWeeklyScores(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        JdbcCursorItemReader<Long> weeklyRankingProductReader,
        WeeklyRankingScoreProcessor weeklyRankingScoreProcessor,
        WeeklyRankingScoreWriter weeklyRankingScoreWriter
    ) {
        return new StepBuilder(CALCULATE_STEP, jobRepository)
            .<Long, RankingProductScore>chunk(CHUNK_SIZE, transactionManager)
            .reader(weeklyRankingProductReader)
            .processor(weeklyRankingScoreProcessor)
            .writer(weeklyRankingScoreWriter)
            .build();
    }

    @Bean(PUBLISH_STEP)
    public Step publishWeeklyRanking(
        JobRepository jobRepository, PlatformTransactionManager transactionManager, RankingPublicationService service
    ) {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(new PublishWeeklyRankingTasklet(service), transactionManager)
            .build();
    }

    @Bean(PRODUCT_READER)
    @StepScope
    public JdbcCursorItemReader<Long> weeklyRankingProductReader(
        DataSource dataSource, @Value("#{stepExecution}") StepExecution stepExecution
    ) {
        String snapshotId = request(stepExecution).snapshotId();
        return new JdbcCursorItemReaderBuilder<Long>()
            .name(PRODUCT_READER)
            .saveState(true)
            .dataSource(dataSource)
            .sql("SELECT DISTINCT product_id FROM ranking_snapshot_input WHERE snapshot_id = ? ORDER BY product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshotId))
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build();
    }

    @Bean
    @StepScope
    public WeeklyRankingScoreProcessor weeklyRankingScoreProcessor(
        RankingScoreRepository repository, @Value("#{stepExecution}") StepExecution stepExecution
    ) {
        return new WeeklyRankingScoreProcessor(request(stepExecution), repository);
    }

    @Bean
    @StepScope
    public WeeklyRankingScoreWriter weeklyRankingScoreWriter(
        RankingScoreRepository repository, @Value("#{stepExecution}") StepExecution stepExecution
    ) {
        return new WeeklyRankingScoreWriter(request(stepExecution), repository);
    }

    private static WeeklyRankingRequest request(StepExecution stepExecution) {
        return WeeklyRankingParameters.parse(stepExecution.getJobParameters());
    }
}
