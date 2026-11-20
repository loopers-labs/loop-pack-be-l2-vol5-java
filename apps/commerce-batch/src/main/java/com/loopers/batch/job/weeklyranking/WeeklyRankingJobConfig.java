package com.loopers.batch.job.weeklyranking;

import com.loopers.application.ranking.WeeklyRankingSnapshotService;
import com.loopers.application.ranking.WeeklyRankingPublicationService;
import com.loopers.domain.ranking.ProductScore;
import com.loopers.domain.ranking.RankingScoreStore;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import javax.sql.DataSource;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
public class WeeklyRankingJobConfig {
    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";
    public static final String SCORE_STEP = "calculateWeeklyScores";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";
    public static final String READER_NAME = "weeklyRankingProducts";

    @Bean(JOB_NAME)
    public Job weeklyRanking(JobRepository repository, @Qualifier(FREEZE_STEP) Step freeze,
                             @Qualifier(SCORE_STEP) Step calculate, @Qualifier(PUBLISH_STEP) Step publish) {
        return new JobBuilder(JOB_NAME, repository).validator(new WeeklyRankingJobParameters())
            .start(freeze).next(calculate).next(publish).build();
    }

    @Bean(PUBLISH_STEP)
    public Step publishWeeklyRanking(JobRepository repository, PlatformTransactionManager transactionManager,
                                     @Qualifier("weeklyRankingPublishTasklet") Tasklet tasklet) {
        return new StepBuilder(PUBLISH_STEP, repository).tasklet(tasklet, transactionManager)
            .transactionAttribute(repeatableRead()).build();
    }

    @Bean("weeklyRankingPublishTasklet")
    @StepScope
    public Tasklet publishTasklet(WeeklyRankingPublicationService publications,
                                 @Value("#{stepExecution.jobParameters}") JobParameters parameters) {
        return (contribution, context) -> {
            publications.publish(WeeklyRankingJobParameters.key(parameters));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean(FREEZE_STEP)
    public Step freezeWeeklyInput(JobRepository repository, PlatformTransactionManager transactionManager,
                                  @Qualifier("weeklyRankingFreezeTasklet") Tasklet tasklet) {
        return new StepBuilder(FREEZE_STEP, repository).tasklet(tasklet, transactionManager)
            .transactionAttribute(repeatableRead()).build();
    }

    @Bean(SCORE_STEP)
    public Step calculateWeeklyScores(JobRepository repository, PlatformTransactionManager transactionManager,
                                     @Qualifier(READER_NAME) JdbcCursorItemReader<Long> reader,
                                     @Qualifier("weeklyRankingProcessor") ItemProcessor<Long, ProductScore> processor,
                                     @Qualifier("weeklyRankingWriter") ItemWriter<ProductScore> writer,
                                     @Value("${commerce.ranking.chunk-size:2}") int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Ranking chunk size must be positive");
        }
        return new StepBuilder(SCORE_STEP, repository).<Long, ProductScore>chunk(chunkSize, transactionManager)
            .reader(reader).processor(processor).writer(writer).transactionAttribute(repeatableRead()).build();
    }

    @Bean("weeklyRankingFreezeTasklet")
    @StepScope
    public Tasklet freezeTasklet(WeeklyRankingSnapshotService snapshots,
                                @Value("#{stepExecution.jobParameters}") JobParameters parameters) {
        return (contribution, context) -> {
            snapshots.freeze(WeeklyRankingJobParameters.key(parameters));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean(READER_NAME)
    @StepScope
    public JdbcCursorItemReader<Long> productReader(@Qualifier("mySqlMainDataSource") DataSource dataSource,
                                                  @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return new JdbcCursorItemReaderBuilder<Long>().name(READER_NAME).saveState(true).dataSource(dataSource)
            .sql("SELECT DISTINCT product_id FROM weekly_ranking_input WHERE snapshot_id=? ORDER BY product_id")
            .preparedStatementSetter(statement -> statement.setString(1, snapshotId))
            .rowMapper((row, number) -> row.getLong("product_id")).build();
    }

    @Bean("weeklyRankingProcessor")
    @StepScope
    public ItemProcessor<Long, ProductScore> scoreProcessor(RankingScoreStore store,
                                                          @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return productId -> store.aggregate(snapshotId, productId);
    }

    @Bean("weeklyRankingWriter")
    @StepScope
    public ItemWriter<ProductScore> scoreWriter(RankingScoreStore store,
                                              @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return chunk -> store.writeScores(snapshotId, List.copyOf(chunk.getItems()));
    }

    private DefaultTransactionAttribute repeatableRead() {
        // Preserve TaskletStep's rollback-on-any-failure default when changing the isolation level.
        var attribute = new DefaultTransactionAttribute() {
            @Override
            public boolean rollbackOn(Throwable exception) {
                return true;
            }
        };
        attribute.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return attribute;
    }
}
