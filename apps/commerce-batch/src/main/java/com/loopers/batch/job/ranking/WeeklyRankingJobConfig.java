package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import com.loopers.batch.job.ranking.step.OpenWeeklyRankingTasklet;
import com.loopers.batch.job.ranking.step.WeeklyScoreStepComponents;
import com.loopers.domain.ranking.ProductScore;
import com.loopers.domain.ranking.WeeklySnapshotId;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.ItemProcessListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/**
 * Job "weeklyRanking" = 입력 고정(Step 1) → 점수 계산(Step 2) → 공개(Step 3). 설계 D1·D9·D10·D11.
 *
 * <ul>
 *   <li>식별 파라미터는 periodStart·snapshotId 두 개뿐. RunIdIncrementer 를 붙이지 않아 같은 파라미터 재실행이 같은 JobInstance 의
 *       재시작이 된다 (D1). preventRestart 도 두지 않는다(기본: 재시작 허용).</li>
 *   <li>TX 매니저는 컨텍스트의 PlatformTransactionManager 빈 하나(JpaTransactionManager, probe 로 확인)라 @Qualifier 가 없다.
 *       JobRepository 도 같은 빈을 쓰므로 chunk commit 과 checkpoint 가 같은 TX 에 저장된다.</li>
 *   <li>Demo 의 JobListener·StepMonitorListener 는 붙이지 않는다. StepMonitorListener.afterStep 이 ExitStatus 를 돌려줘
 *       Step 3 의 D8 exitDescription 과 합성되는데, 그 동작은 지금 검증 범위가 아니다. 필요하면 ⑥ 이후 재검토.</li>
 * </ul>
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";

    /** 과제 §6 재시작 실험값. 실제 데이터의 처리량을 측정한 값이 아니다 (D10). */
    static final int CHUNK_SIZE = 2;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final FreezeWeeklyInputTasklet freezeWeeklyInputTasklet;
    private final OpenWeeklyRankingTasklet openWeeklyRankingTasklet;
    @Qualifier(WeeklyScoreStepComponents.READER_NAME)
    private final JdbcCursorItemReader<Long> weeklyProductIdReader;
    @Qualifier("weeklyScoreProcessor")
    private final ItemProcessor<Long, ProductScore> weeklyScoreProcessor;
    @Qualifier("weeklyScoreWriter")
    private final ItemWriter<ProductScore> weeklyScoreWriter;
    /** 선택 주입. 제품에는 없고, 테스트가 Step 2 중간 실패 지점을 만들 때 등록한다 (Spring Batch 표준 확장점). */
    private final ObjectProvider<ItemProcessListener<Long, ProductScore>> processListeners;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(weeklyRankingParametersValidator())
            .start(freezeWeeklyInputStep())
            .next(calculateWeeklyScoresStep())
            .next(openWeeklyRankingStep())
            .build();
    }

    /**
     * 식별 파라미터 1차 검증 (D1). Job 시작 전에 거절하므로 잘못된 파라미터로 FAILED JobInstance 가 metadata 에 남지 않는다.
     * 규칙은 WeeklySnapshotId 와 같다(존재·형식·날짜 일치·월요일·revision ≥ 1). Step 1 Tasklet 의 같은 검증은 2차 방어로 그대로 둔다.
     */
    static JobParametersValidator weeklyRankingParametersValidator() {
        return parameters -> {
            try {
                WeeklySnapshotId.of(parameters.getString("snapshotId"), parameters.getString("periodStart"));
            } catch (IllegalArgumentException e) {
                throw new JobParametersInvalidException(e.getMessage());
            }
        };
    }

    /**
     * Step 1. 한 TX, READ_COMMITTED (D9): REPEATABLE READ 에서는 INSERT ... SELECT 가 원본 행에 공유 락을 걸어 consumer 저장을 막을 수 있다.
     * allowStartIfComplete 는 기본값(false) — 재시작 시 완료된 입력 고정은 다시 실행하지 않는다.
     */
    @Bean(FreezeWeeklyInputTasklet.STEP_NAME)
    public Step freezeWeeklyInputStep() {
        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_REQUIRED);
        attribute.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new StepBuilder(FreezeWeeklyInputTasklet.STEP_NAME, jobRepository)
            .tasklet(freezeWeeklyInputTasklet, transactionManager)
            .transactionAttribute(attribute)
            .build();
    }

    /** Step 2. chunk 단위 commit, faultTolerant·skip·retry 없음 — 잘못된 입력은 실패를 드러낸다 (D6 원칙). */
    @Bean(WeeklyScoreStepComponents.STEP_NAME)
    public Step calculateWeeklyScoresStep() {
        SimpleStepBuilder<Long, ProductScore> builder = new StepBuilder(WeeklyScoreStepComponents.STEP_NAME, jobRepository)
            .<Long, ProductScore>chunk(CHUNK_SIZE, transactionManager)
            .reader(weeklyProductIdReader)
            .processor(weeklyScoreProcessor)
            .writer(weeklyScoreWriter);
        processListeners.orderedStream().forEach(builder::listener);
        return builder.build();
    }

    /** Step 3. 한 TX, 기본 isolation (D11). */
    @Bean(OpenWeeklyRankingTasklet.STEP_NAME)
    public Step openWeeklyRankingStep() {
        return new StepBuilder(OpenWeeklyRankingTasklet.STEP_NAME, jobRepository)
            .tasklet(openWeeklyRankingTasklet, transactionManager)
            .build();
    }
}
