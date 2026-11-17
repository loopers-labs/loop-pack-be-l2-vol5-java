package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import com.loopers.batch.job.ranking.step.PublishWeeklyRankingTasklet;
import com.loopers.batch.job.ranking.step.WeeklyScoreProcessor;
import com.loopers.batch.job.ranking.step.WeeklyScoreWriter;
import com.loopers.batch.listener.JobListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 주간 구매 랭킹 job (docs/week9/design.md).
 * <ul>
 *   <li>식별 parameter는 periodStart·snapshotId뿐이다. RunIdIncrementer를 쓰지 않으므로, 실패한 실행은 같은 parameter로 같은 JobInstance를 이어 간다.</li>
 *   <li>모든 step은 Boot의 실제 DB 트랜잭션 매니저를 쓴다. 결과 쓰기와 Batch metadata(checkpoint)가 같은 DataSource에서 함께 commit된다.</li>
 *   <li>StepMonitorListener는 붙이지 않는다. 실패가 없으면 exit status를 COMPLETED로 덮어써서, step이 남긴 상태(ALREADY_FROZEN 등)를 지운다.</li>
 * </ul>
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";
    public static final String CALCULATE_STEP = "calculateWeeklyScores";
    public static final String PRODUCT_READER = "weeklyProductReader";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobListener jobListener;

    public WeeklyRankingJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager, JobListener jobListener) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.jobListener = jobListener;
    }

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(
        @Qualifier(FREEZE_STEP) Step freezeWeeklyInputStep,
        @Qualifier(CALCULATE_STEP) Step calculateWeeklyScoresStep,
        @Qualifier(PUBLISH_STEP) Step publishWeeklyRankingStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(parametersValidator())
            .start(freezeWeeklyInputStep)
            .next(calculateWeeklyScoresStep)
            .next(publishWeeklyRankingStep)
            .listener(jobListener)
            .build();
    }

    /** ③ 완성본 공개. 완성 표시와 공개 pointer 변경을 한 트랜잭션에서 한다. */
    @Bean(PUBLISH_STEP)
    public Step publishWeeklyRankingStep(PublishWeeklyRankingTasklet publishWeeklyRankingTasklet) {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(publishWeeklyRankingTasklet, transactionManager)
            .build();
    }

    /** ① 입력 고정. 재시작 때 이미 COMPLETED면 Spring Batch가 다시 실행하지 않는다(allowStartIfComplete를 켜지 않는다). */
    @Bean(FREEZE_STEP)
    public Step freezeWeeklyInputStep(FreezeWeeklyInputTasklet freezeWeeklyInputTasklet) {
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeWeeklyInputTasklet, transactionManager)
            .build();
    }

    /** ② 점수 계산. 상품 하나가 item 하나이고, 점수와 reader 위치가 chunk마다 같은 트랜잭션으로 commit된다. */
    @Bean(CALCULATE_STEP)
    public Step calculateWeeklyScoresStep(
        @Qualifier(PRODUCT_READER) JdbcCursorItemReader<Long> weeklyProductReader,
        WeeklyScoreProcessor weeklyScoreProcessor,
        WeeklyScoreWriter weeklyScoreWriter,
        @Value("${ranking.weekly.chunk-size:100}") int chunkSize
    ) {
        return new StepBuilder(CALCULATE_STEP, jobRepository)
            .<Long, ProductScore>chunk(chunkSize, transactionManager)
            .reader(weeklyProductReader)
            .processor(weeklyScoreProcessor)
            .writer(weeklyScoreWriter)
            .build();
    }

    /**
     * 고정 입력의 서로 다른 상품 ID를 오름차순으로 읽는다. ID는 snapshot 안에서 유일하고 정렬이 안정적이라 저장한 위치가 재시작 때도 같은 상품을 가리킨다.
     * 이름(PRODUCT_READER)이 ExecutionContext의 key 접두어이므로 바꾸면 기존 실행을 이어 갈 수 없다.
     */
    @Bean(PRODUCT_READER)
    @StepScope
    public JdbcCursorItemReader<Long> weeklyProductReader(DataSource dataSource, @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return new JdbcCursorItemReaderBuilder<Long>()
            .name(PRODUCT_READER)
            .saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from ranking_snapshot_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshotId))
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build();
    }

    private JobParametersValidator parametersValidator() {
        return parameters -> {
            if (parameters == null) {
                throw new JobParametersInvalidException("periodStart와 snapshotId가 필요합니다.");
            }
            try {
                WeeklySnapshotKey.of(parameters.getString("periodStart"), parameters.getString("snapshotId"));
            } catch (IllegalArgumentException e) {
                throw new JobParametersInvalidException(e.getMessage());
            }
        };
    }
}
