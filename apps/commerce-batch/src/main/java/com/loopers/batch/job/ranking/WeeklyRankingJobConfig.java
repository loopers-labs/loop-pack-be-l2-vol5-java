package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.domain.ProductScore;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.infrastructure.RankingPublicationStore;
import com.loopers.batch.job.ranking.infrastructure.RankingSnapshotStore;
import com.loopers.batch.job.ranking.infrastructure.ScoreStageStore;
import com.loopers.batch.job.ranking.step.FreezeSnapshotTasklet;
import com.loopers.batch.job.ranking.step.PublishRankingTasklet;
import com.loopers.batch.job.ranking.step.ScoreStageWriter;
import com.loopers.batch.job.ranking.step.SnapshotIntegrityListener;
import com.loopers.batch.job.ranking.step.WeeklyScoreProcessor;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.time.Clock;

@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@EnableConfigurationProperties(WeeklyRankingProperties.class)
@Configuration
public class WeeklyRankingJobConfig {
    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeSnapshot";
    public static final String CALCULATE_STEP = "calculateWeeklyScores";
    public static final String PUBLISH_STEP = "publishRanking";
    public static final String PRODUCT_READER = "weeklyRankingProductReader";
    public static final String CLOCK = "weeklyRankingClock";

    private static final String PERIOD_START = "#{jobParameters['periodStart']}";
    private static final String SNAPSHOT_ID = "#{jobParameters['snapshotId']}";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    public WeeklyRankingJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    @Bean(name = CLOCK, defaultCandidate = false)
    public Clock weeklyRankingClock() {
        return Clock.systemUTC();
    }

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(
            @Qualifier(FREEZE_STEP) Step freeze,
            @Qualifier(CALCULATE_STEP) Step calculate,
            @Qualifier(PUBLISH_STEP) Step publish,
            JobExplorer jobExplorer) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(new WeeklyRankingParametersValidator(jobExplorer))
                .listener(new SerialPeriodGuard(jobExplorer))
                .start(freeze)
                .next(calculate)
                .next(publish)
                .build();
    }

    @Bean
    public WeeklyRankingRecovery weeklyRankingRecovery(JobExplorer jobExplorer) {
        return new WeeklyRankingRecovery(jobExplorer, jobRepository, transactionManager);
    }

    @Bean
    @Conditional(WeeklyRankingRecoveryRequested.class)
    public WeeklyRankingRecoveryRunner weeklyRankingRecoveryRunner(
            WeeklyRankingRecovery recovery,
            @Value("${weekly-ranking.recovery.execution-id}") long executionId,
            @Value("${weekly-ranking.recovery.termination-confirmed:false}") boolean terminationConfirmed,
            @Value("${spring.batch.job.enabled:true}") boolean automaticJobEnabled) {
        return new WeeklyRankingRecoveryRunner(recovery, executionId, terminationConfirmed, automaticJobEnabled);
    }

    // REPEATABLE READ의 INSERT ... SELECT는 원본 전체에 공유 잠금을 걸어 consumer 저장을 막는다.
    @Bean(FREEZE_STEP)
    public Step freezeSnapshotStep(FreezeSnapshotTasklet freezeSnapshotTasklet) {
        return new StepBuilder(FREEZE_STEP, jobRepository)
                .tasklet(freezeSnapshotTasklet, transactionManager)
                .transactionAttribute(readCommitted())
                .build();
    }

    @Bean(CALCULATE_STEP)
    public Step calculateWeeklyScoresStep(
            JdbcCursorItemReader<Long> weeklyRankingProductReader,
            WeeklyScoreProcessor weeklyScoreProcessor,
            ScoreStageWriter scoreStageWriter,
            SnapshotIntegrityListener snapshotIntegrityListener,
            WeeklyRankingProperties properties) {
        return new StepBuilder(CALCULATE_STEP, jobRepository)
                .<Long, ProductScore>chunk(properties.chunkSize(), transactionManager)
                .reader(weeklyRankingProductReader)
                .processor(weeklyScoreProcessor)
                .writer(scoreStageWriter)
                .listener(snapshotIntegrityListener)
                .build();
    }

    @Bean(PUBLISH_STEP)
    public Step publishRankingStep(PublishRankingTasklet publishRankingTasklet) {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
                .tasklet(publishRankingTasklet, transactionManager)
                .transactionAttribute(readCommitted())
                .build();
    }

    @Bean
    @StepScope
    public FreezeSnapshotTasklet freezeSnapshotTasklet(
            @Value(PERIOD_START) String periodStart, @Value(SNAPSHOT_ID) String snapshotId,
            @Value("#{jobParameters['sourceSnapshotId']}") String sourceSnapshotId,
            RankingSnapshotStore snapshots, @Qualifier(CLOCK) Clock clock) {
        var parameters = WeeklyRankingParameters.of(periodStart, snapshotId, sourceSnapshotId);
        return new FreezeSnapshotTasklet(snapshots, clock, parameters);
    }

    // 반환 타입이 ItemStream 구현체여야 step scope proxy가 stream으로 등록되어 위치가 저장된다.
    @Bean(PRODUCT_READER)
    @StepScope
    public JdbcCursorItemReader<Long> weeklyRankingProductReader(
            @Value(PERIOD_START) String periodStart, @Value(SNAPSHOT_ID) String snapshotId,
            RankingSnapshotStore snapshots) {
        return snapshots.productIdReader(PRODUCT_READER, snapshotId(periodStart, snapshotId));
    }

    @Bean
    @StepScope
    public SnapshotIntegrityListener snapshotIntegrityListener(
            @Value(PERIOD_START) String periodStart, @Value(SNAPSHOT_ID) String snapshotId,
            RankingSnapshotStore snapshots) {
        return new SnapshotIntegrityListener(snapshots, snapshotId(periodStart, snapshotId));
    }

    @Bean
    @StepScope
    public WeeklyScoreProcessor weeklyScoreProcessor(
            @Value(PERIOD_START) String periodStart, @Value(SNAPSHOT_ID) String snapshotId,
            RankingSnapshotStore snapshots) {
        return new WeeklyScoreProcessor(snapshots, snapshotId(periodStart, snapshotId));
    }

    @Bean
    @StepScope
    public ScoreStageWriter scoreStageWriter(
            @Value(PERIOD_START) String periodStart, @Value(SNAPSHOT_ID) String snapshotId,
            ScoreStageStore stages) {
        return new ScoreStageWriter(stages, snapshotId(periodStart, snapshotId));
    }

    @Bean
    @StepScope
    public PublishRankingTasklet publishRankingTasklet(
            @Value(PERIOD_START) String periodStart, @Value(SNAPSHOT_ID) String snapshotId,
            RankingSnapshotStore snapshots, ScoreStageStore stages, RankingPublicationStore publications,
            @Qualifier(CLOCK) Clock clock) {
        return new PublishRankingTasklet(snapshots, stages, publications, clock, snapshotId(periodStart, snapshotId));
    }

    private static SnapshotId snapshotId(String periodStart, String snapshotId) {
        return WeeklyRankingParameters.of(periodStart, snapshotId).snapshotId();
    }

    private static TransactionAttribute readCommitted() {
        var attribute = new DefaultTransactionAttribute();
        attribute.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return attribute;
    }
}
