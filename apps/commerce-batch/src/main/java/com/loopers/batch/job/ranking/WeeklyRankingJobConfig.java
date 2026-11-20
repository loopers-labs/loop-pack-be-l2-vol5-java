package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.FreezeSnapshotTasklet;
import com.loopers.batch.job.ranking.step.PublishRankingTasklet;
import com.loopers.batch.job.ranking.step.ScoreStageWriter;
import com.loopers.batch.job.ranking.step.SnapshotProductPagingReader;
import com.loopers.batch.job.ranking.step.SnapshotScoreProcessor;
import com.loopers.batch.listener.JobListener;
import com.loopers.batch.listener.StepMonitorListener;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import javax.sql.DataSource;

/**
 * 주간 구매 수량 랭킹 job. 입력 고정(tasklet) → 상품별 점수 계산(chunk) → 완성본 공개(tasklet) 순서다.
 * 식별 parameter는 periodStart·snapshotId이며 incrementer를 두지 않는다. 같은 값으로 다시 실행하면 같은 JobInstance의 재시작이다.
 * 모든 Step은 업무 데이터와 Batch metadata가 같은 DB를 쓰는 기본 트랜잭션 관리자로 commit한다.
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {
    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeSnapshotInput";
    public static final String SCORE_STEP = "calculateWeeklyScores";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";
    public static final String PRODUCT_READER = "snapshotProductReader";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobListener jobListener;
    private final StepMonitorListener stepMonitorListener;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(Step freezeSnapshotInput, Step calculateWeeklyScores, Step publishWeeklyRanking) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new WeeklyRankingParametersValidator())
            .start(freezeSnapshotInput)
            .next(calculateWeeklyScores)
            .next(publishWeeklyRanking)
            .listener(jobListener)
            .build();
    }

    /** 원본을 한 문장으로 복사하므로 이 Step의 TX만 READ COMMITTED로 실행해 원본을 잠그지 않는다(ADR 0085). */
    @Bean(FREEZE_STEP)
    public Step freezeSnapshotInput(FreezeSnapshotTasklet freezeSnapshotTasklet) {
        DefaultTransactionAttribute readCommitted = new DefaultTransactionAttribute();
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeSnapshotTasklet, transactionManager)
            .transactionAttribute(readCommitted)
            .listener(stepMonitorListener)
            .build();
    }

    @Bean(SCORE_STEP)
    public Step calculateWeeklyScores(
        SnapshotProductPagingReader snapshotProductReader,
        SnapshotScoreProcessor snapshotScoreProcessor,
        ScoreStageWriter scoreStageWriter,
        @Value("${commerce.ranking.weekly.chunk-size}") int chunkSize
    ) {
        return new StepBuilder(SCORE_STEP, jobRepository)
            .<Long, ProductScore>chunk(chunkSize, transactionManager)
            .reader(snapshotProductReader)
            .processor(snapshotScoreProcessor)
            .writer(scoreStageWriter)
            .listener(stepMonitorListener)
            .build();
    }

    /** 완성 조건 검증·완성 랭킹 저장·완성 표시·공개 pointer 교체를 기본 격리 수준의 한 TX로 한다. */
    @Bean(PUBLISH_STEP)
    public Step publishWeeklyRanking(PublishRankingTasklet publishRankingTasklet) {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(publishRankingTasklet, transactionManager)
            .listener(stepMonitorListener)
            .build();
    }

    /** 고정 입력의 서로 다른 상품 ID를 마지막 상품 ID 기준으로 페이지 조회한다. */
    @Bean(PRODUCT_READER)
    @StepScope
    public SnapshotProductPagingReader snapshotProductReader(
        @Qualifier("mySqlMainDataSource") DataSource dataSource,
        @Value("#{jobParameters['snapshotId']}") String snapshotId,
        @Value("${commerce.ranking.weekly.chunk-size}") int chunkSize
    ) {
        return new SnapshotProductPagingReader(PRODUCT_READER, dataSource, snapshotId, chunkSize);
    }
}
