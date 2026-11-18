package com.loopers.batch.job.ranking;

import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

// 주간 랭킹(docs/week9/commerce-ranking.md). 식별 parameter는 periodStart·snapshotId이고 incrementer가 없어,
// 같은 parameter로 다시 실행하면 같은 JobInstance의 재시작이다. 완료된 Step 1은 재시작 때 다시 돌지 않고 Step 2가 읽은 위치에서 이어 간다.
// 확장 job(weeklyRankingExtend)은 같은 공개 tasklet을 쓰므로 두 이름 중 하나면 이 설정을 올린다.
@ConditionalOnExpression("'${spring.batch.job.name:}' == '" + WeeklyRankingJobConfig.JOB_NAME
    + "' or '${spring.batch.job.name:}' == '" + WeeklyRankingJobConfig.EXTEND_JOB_NAME + "'")
@Configuration
public class WeeklyRankingJobConfig {
    public static final String JOB_NAME = "weeklyRanking";
    public static final String EXTEND_JOB_NAME = "weeklyRankingExtend";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";
    public static final String FREEZE_STEP = "freezeInput";
    public static final String CALCULATE_STEP = "calculateWeeklyScores";
    public static final String READER = "weeklyRankingProduct";

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(JobRepository jobRepository, Step freezeInputStep, Step calculateWeeklyScoresStep,
        Step publishWeeklyRankingStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(weeklyRankingParametersValidator())
            .start(freezeInputStep)
            .next(calculateWeeklyScoresStep)
            .next(publishWeeklyRankingStep)
            .build();
    }

    // K 확장(8절). 이미 고정·계산된 snapshot에 공개 Step만 다시 돌린다. 식별값 snapshotId·rankingSize.
    @Bean(EXTEND_JOB_NAME)
    public Job weeklyRankingExtendJob(JobRepository jobRepository, Step publishWeeklyRankingStep) {
        return new JobBuilder(EXTEND_JOB_NAME, jobRepository)
            .validator(extendParametersValidator())
            .start(publishWeeklyRankingStep)
            .build();
    }

    @Bean
    public Step publishWeeklyRankingStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
        PublishRankingTasklet publishRankingTasklet) {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(publishRankingTasklet, transactionManager)
            .build();
    }

    @StepScope
    @Bean
    public PublishRankingTasklet publishRankingTasklet(WeeklyRankingRepository weeklyRankingRepository,
        @Value("#{jobParameters['snapshotId']}") String snapshotId, @Value("#{jobParameters['rankingSize']}") Long rankingSize,
        @Value("${commerce.ranking.size}") int defaultRankingSize) {
        return new PublishRankingTasklet(weeklyRankingRepository, snapshotId,
            rankingSize == null ? defaultRankingSize : Math.toIntExact(rankingSize));
    }

    @Bean
    public WeeklyRankingLauncher weeklyRankingLauncher(JobLauncher jobLauncher, JobExplorer jobExplorer, Job weeklyRanking) {
        return new WeeklyRankingLauncher(jobLauncher, jobExplorer, weeklyRanking);
    }

    @Bean
    public Step freezeInputStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
        FreezeInputTasklet freezeInputTasklet) {
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeInputTasklet, transactionManager)
            .build();
    }

    @StepScope
    @Bean
    public FreezeInputTasklet freezeInputTasklet(WeeklyRankingRepository weeklyRankingRepository, Clock clock,
        @Value("#{jobParameters['periodStart']}") String periodStart,
        @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return new FreezeInputTasklet(weeklyRankingRepository, clock, WeeklyRankingParameters.parse(periodStart, snapshotId));
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

    // reader 이름이 ExecutionContext 키(weeklyRankingProduct.read.count)가 되므로 바꾸지 않는다. snapshot 입력만 읽는다.
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
    public ItemProcessor<Long, ProductScore> weeklyScoreProcessor(WeeklyRankingRepository weeklyRankingRepository,
        @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return productId -> new ProductScore(snapshotId, productId, weeklyRankingRepository.scoreOf(snapshotId, productId));
    }

    @Bean
    public ItemWriter<ProductScore> weeklyScoreWriter(WeeklyRankingRepository weeklyRankingRepository) {
        return chunk -> weeklyRankingRepository.upsertScores(List.copyOf(chunk.getItems()));
    }

    @Bean
    public WeeklyRankingRepository weeklyRankingRepository(JdbcTemplate jdbcTemplate) {
        return new WeeklyRankingRepository(jdbcTemplate);
    }

    @Bean
    public Clock weeklyRankingClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "ranking.period")
    public WeeklyRankingLauncherRunner weeklyRankingLauncherRunner(WeeklyRankingLauncher weeklyRankingLauncher,
        @Value("${spring.batch.job.enabled:true}") boolean defaultRunnerEnabled) {
        return new WeeklyRankingLauncherRunner(weeklyRankingLauncher, defaultRunnerEnabled);
    }

    // 날짜가 어긋나거나 periodStart가 월요일이 아니거나 rankingSize가 1보다 작으면 Job을 시작하지 않는다(D152).
    private static JobParametersValidator weeklyRankingParametersValidator() {
        return parameters -> {
            if (parameters == null) {
                throw new JobParametersInvalidException("periodStart와 snapshotId가 필요합니다.");
            }
            try {
                WeeklyRankingParameters.parse(parameters.getString(WeeklyRankingParameters.PERIOD_START),
                    parameters.getString(WeeklyRankingParameters.SNAPSHOT_ID));
                WeeklyRankingParameters.requireRankingSize(parameters.getLong(WeeklyRankingParameters.RANKING_SIZE));
            } catch (IllegalArgumentException e) {
                throw new JobParametersInvalidException(e.getMessage());
            }
        };
    }

    // 확장은 이미 있는 snapshot 하나와 새 K를 가리킨다. 둘 다 있어야 한다.
    private static JobParametersValidator extendParametersValidator() {
        return parameters -> {
            if (parameters == null || parameters.getLong(WeeklyRankingParameters.RANKING_SIZE) == null) {
                throw new JobParametersInvalidException("snapshotId와 rankingSize가 필요합니다.");
            }
            try {
                WeeklyRankingParameters.fromSnapshotId(parameters.getString(WeeklyRankingParameters.SNAPSHOT_ID));
                WeeklyRankingParameters.requireRankingSize(parameters.getLong(WeeklyRankingParameters.RANKING_SIZE));
            } catch (IllegalArgumentException e) {
                throw new JobParametersInvalidException(e.getMessage());
            }
        };
    }
}
