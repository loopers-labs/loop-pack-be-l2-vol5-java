package com.loopers.batch.job.ranking;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.loopers.batch.job.ranking.step.FreezeSnapshotTasklet;
import com.loopers.batch.job.ranking.step.PublishRankingTasklet;
import com.loopers.batch.job.ranking.step.ScoreStageWriter;

@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration
class WeeklyRankingJobConfig {

    static final String JOB_NAME = "weeklyRanking";

    @Bean(JOB_NAME)
    Job weeklyRanking(
        JobRepository jobRepository, Step freezeSnapshotStep, Step calculateScoreStep, Step publishRankingStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new WeeklyRankingParametersValidator())
            .start(freezeSnapshotStep)
            .next(calculateScoreStep)
            .next(publishRankingStep)
            .build();
    }

    @Bean
    Step publishRankingStep(
        JobRepository jobRepository, PlatformTransactionManager transactionManager, DataSource dataSource
    ) {
        return new StepBuilder("publishRanking", jobRepository)
            .tasklet(new PublishRankingTasklet(new PublishedRankingStore(new JdbcTemplate(dataSource))), transactionManager)
            .build();
    }

    @Bean
    Step freezeSnapshotStep(
        JobRepository jobRepository, PlatformTransactionManager transactionManager, DataSource dataSource
    ) {
        return new StepBuilder("freezeSnapshot", jobRepository)
            .tasklet(new FreezeSnapshotTasklet(new SnapshotStore(new JdbcTemplate(dataSource))), transactionManager)
            .build();
    }

    @Bean
    Step calculateScoreStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        ItemStreamReader<Long> snapshotProductReader,
        ItemWriter<Long> productScoreWriter,
        @Value("${ranking.chunk-size}") int chunkSize
    ) {
        return new StepBuilder("calculateScore", jobRepository)
            .<Long, Long>chunk(chunkSize, transactionManager)
            .reader(snapshotProductReader)
            .writer(productScoreWriter)
            .build();
    }

    @Bean
    @StepScope
    ItemStreamReader<Long> snapshotProductReader(
        DataSource dataSource,
        @Value("#{jobParameters['snapshotId']}") String snapshotId,
        @Value("${ranking.chunk-size}") int chunkSize
    ) {
        MySqlPagingQueryProvider query = new MySqlPagingQueryProvider();
        query.setSelectClause("distinct product_id");
        query.setFromClause("ranking_snapshot_input");
        query.setWhereClause("snapshot_id = :snapshotId");
        query.setSortKeys(Map.of("product_id", Order.ASCENDING));
        return new JdbcPagingItemReaderBuilder<Long>()
            .name("snapshotProductReader")
            .dataSource(dataSource)
            .queryProvider(query)
            .parameterValues(Map.of("snapshotId", snapshotId))
            .pageSize(chunkSize)
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build();
    }

    @Bean
    @StepScope
    ItemWriter<Long> productScoreWriter(
        DataSource dataSource,
        @Value("#{jobParameters['snapshotId']}") String snapshotId
    ) {
        return new ScoreStageWriter(new NamedParameterJdbcTemplate(dataSource), snapshotId);
    }
}
