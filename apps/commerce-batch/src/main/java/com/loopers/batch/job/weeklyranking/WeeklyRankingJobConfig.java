package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.job.weeklyranking.freeze.FreezeInputTasklet;
import com.loopers.batch.job.weeklyranking.publish.PublishTasklet;
import com.loopers.batch.job.weeklyranking.score.WeeklyScoreProcessor;
import com.loopers.batch.job.weeklyranking.score.WeeklyScoreWriter;
import com.loopers.domain.ranking.score.ProductScore;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaCursorItemReader;
import org.springframework.batch.item.database.builder.JpaCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import java.time.LocalDate;
import java.util.Map;

/**
 * 입력 고정 → 점수 계산 → 완성본 공개. 식별 parameter 는 periodStart·revision 이고 incrementer 를 두지 않는다.
 * 실패하면 같은 parameter 로 다시 실행해 같은 JobInstance 의 마지막 commit 부터 이어 간다.
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";
    public static final String CALCULATE_STEP = "calculateWeeklyScores";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";
    // 재시작 위치가 이 이름으로 ExecutionContext 에 저장된다(<name>.read.count). 바꾸면 기존 실행을 이어 가지 못한다.
    public static final String PRODUCT_READER = "weeklyRankingProductReader";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final FreezeInputTasklet freezeInputTasklet;
    private final WeeklyScoreProcessor weeklyScoreProcessor;
    private final WeeklyScoreWriter weeklyScoreWriter;
    private final PublishTasklet publishTasklet;

    @Value("${ranking.weekly.chunk-size}")
    private int chunkSize;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new DefaultJobParametersValidator(
                new String[] {WeeklyRankingJobParameters.PERIOD_START, WeeklyRankingJobParameters.REVISION},
                new String[] {}))
            .start(freezeInputStep())
            .next(calculateScoresStep())
            .next(publishStep())
            .build();
    }

    @Bean(FREEZE_STEP)
    public Step freezeInputStep() {
        // 원본을 잠그지 않는 일관된 읽기로 복사해 W6 consumer 의 insert 를 막지 않는다.
        DefaultTransactionAttribute readCommitted = new DefaultTransactionAttribute();
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeInputTasklet, transactionManager)
            .transactionAttribute(readCommitted)
            .build();
    }

    @Bean(CALCULATE_STEP)
    public Step calculateScoresStep() {
        // 상품 하나가 item 하나다. chunk 결과와 reader 위치가 같은 transactionManager 로 함께 commit 된다.
        return new StepBuilder(CALCULATE_STEP, jobRepository)
            .<Long, ProductScore>chunk(chunkSize, transactionManager)
            .reader(productReader(null, null))
            .processor(weeklyScoreProcessor)
            .writer(weeklyScoreWriter)
            .build();
    }

    @Bean(PUBLISH_STEP)
    public Step publishStep() {
        // 완성 조건 검증·완성 ranking·pointer 변경이 한 TX 다.
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(publishTasklet, transactionManager)
            .build();
    }

    @StepScope
    @Bean
    public JpaCursorItemReader<Long> productReader(
        @Value("#{jobParameters['" + WeeklyRankingJobParameters.PERIOD_START + "']}") LocalDate periodStart,
        @Value("#{jobParameters['" + WeeklyRankingJobParameters.REVISION + "']}") Long revision
    ) {
        return new JpaCursorItemReaderBuilder<Long>()
            .name(PRODUCT_READER)
            .saveState(true)
            .entityManagerFactory(entityManagerFactory)
            .queryString("select distinct i.productId from WeeklyRankingSnapshotInput i "
                + "where i.snapshotId = :snapshotId order by i.productId")
            .parameterValues(Map.of("snapshotId", WeeklyRankingSnapshotIds.of(periodStart, revision)))
            .build();
    }
}
