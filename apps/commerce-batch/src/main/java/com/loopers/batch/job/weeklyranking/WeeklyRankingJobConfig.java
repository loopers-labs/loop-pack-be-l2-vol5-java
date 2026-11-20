package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.ProductScore;
import com.loopers.batch.domain.ProductTotal;
import com.loopers.batch.domain.SnapshotId;
import com.loopers.batch.listener.JobListener;
import com.loopers.batch.listener.JobResultAlertListener;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 주간 구매 랭킹 job (W9). 입력 고정 step → 점수 계산 step → 공개 step.
 * 작업 식별 parameter = periodStart + revision (D-1). 재시작하면 끝난 step은 건너뛰고, 실패한 step은 마지막 commit 다음부터 이어 간다.
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRankingJob";
    public static final String INPUT_FREEZE_STEP = "inputFreezeStep";
    public static final String SCORE_STEP = "weeklyScoreStep";
    /** reader 이름은 실행 기록의 key가 된다 (weeklyScoreReader.read.count). 바꾸면 재시작이 이전 위치를 못 찾는다 */
    public static final String SCORE_READER = "weeklyScoreReader";
    public static final String PUBLISH_STEP = "publishStep";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final Clock clock;
    private final JobListener jobListener;
    private final JobResultAlertListener jobResultAlertListener;
    private final InputFreezeTasklet inputFreezeTasklet;
    private final WeeklyScoreProcessor weeklyScoreProcessor;
    private final PublishTasklet publishTasklet;

    /** 설정값이다. 작업 식별 parameter가 아니므로 실행마다 바꿔도 같은 작업이다 (D-4) */
    @Value("${weekly-ranking.chunk-size:1000}")
    private int chunkSize;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new WeeklyRankingJobParametersValidator())
            .start(inputFreezeStep())
            .next(weeklyScoreStep())
            .next(publishStep())
            .listener(jobListener)
            .listener(jobResultAlertListener)
            .build();
    }

    /**
     * TX 하나, READ COMMITTED — 복사하는 동안 원본(구매 활동)을 잠그지 않는다 (D-3).
     */
    @Bean(INPUT_FREEZE_STEP)
    public Step inputFreezeStep() {
        // TaskletStep 기본값처럼 모든 예외에서 rollback한다. DefaultTransactionAttribute를 그대로 쓰면 checked 예외에서는 commit된다.
        DefaultTransactionAttribute readCommitted = new DefaultTransactionAttribute() {
            @Override
            public boolean rollbackOn(Throwable ex) {
                return true;
            }
        };
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new StepBuilder(INPUT_FREEZE_STEP, jobRepository)
            .tasklet(inputFreezeTasklet, transactionManager)
            .transactionAttribute(readCommitted)
            .build();
    }

    /**
     * chunk마다 TX 하나 = 점수 행 쓰기 + reader 위치 저장 (D-4). retry·skip은 두지 않는다 (D-7).
     */
    @Bean(SCORE_STEP)
    public Step weeklyScoreStep() {
        return new StepBuilder(SCORE_STEP, jobRepository)
            .<ProductTotal, ProductScore>chunk(chunkSize, transactionManager)
            .reader(weeklyScoreReader(null, null))
            .processor(weeklyScoreProcessor)
            .writer(weeklyScoreWriter(null, null))
            .build();
    }

    /**
     * 고정 입력을 상품 ID 순으로 묶어 흘려 읽는다. UNIQUE (snapshot_id, product_id, order_id) 순서라 정렬·임시 테이블이 없다 (D-4).
     * cursor는 chunk TX와 다른 자기 연결을 쓴다. 재시작하면 같은 SQL을 다시 열고 저장된 위치만큼 건너뛴다.
     */
    @Bean
    @StepScope
    public JdbcCursorItemReader<ProductTotal> weeklyScoreReader(
        @Value("#{jobParameters['periodStart']}") LocalDate periodStart,
        @Value("#{jobParameters['revision']}") Long revision
    ) {
        String snapshotId = SnapshotId.of(periodStart, revision).value();
        return new JdbcCursorItemReaderBuilder<ProductTotal>()
            .name(SCORE_READER)
            .dataSource(dataSource)
            .sql("""
                SELECT product_id, SUM(quantity) AS total
                  FROM ranking_snapshot_input
                 WHERE snapshot_id = ?
                 GROUP BY product_id
                 ORDER BY product_id
                """)
            .preparedStatementSetter(ps -> ps.setString(1, snapshotId))
            .fetchSize(Integer.MIN_VALUE)
            // MySQL 흘려 읽기 결과 집합은 getRow()를 지원하지 않는다. 이 확인은 row mapper가 cursor를 옮기지 않았는지 보는 것인데,
            // 아래 row mapper는 컬럼만 읽으므로 꺼도 안전하다.
            .verifyCursorPosition(false)
            .rowMapper((rs, rowNum) -> new ProductTotal(rs.getLong("product_id"), rs.getLong("total")))
            .saveState(true)
            .build();
    }

    /**
     * 점수를 절대값으로 덮어쓴다 (upsert). 같은 상품을 두 번 써도 결과가 같다. rank_no는 건드리지 않는다 (D-4).
     */
    @Bean
    @StepScope
    public JdbcBatchItemWriter<ProductScore> weeklyScoreWriter(
        @Value("#{jobParameters['periodStart']}") LocalDate periodStart,
        @Value("#{jobParameters['revision']}") Long revision
    ) {
        String snapshotId = SnapshotId.of(periodStart, revision).value();
        return new JdbcBatchItemWriterBuilder<ProductScore>()
            .dataSource(dataSource)
            .sql("""
                INSERT INTO weekly_ranking (snapshot_id, product_id, score, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE score = new.score, updated_at = new.updated_at
                """)
            .itemPreparedStatementSetter((item, ps) -> {
                LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
                ps.setString(1, snapshotId);
                ps.setLong(2, item.productId());
                ps.setLong(3, item.score());
                ps.setObject(4, nowUtc);
                ps.setObject(5, nowUtc);
            })
            .assertUpdates(true)
            .build();
    }

    /**
     * TX 하나 — 완성 확인·순위·COMPLETED·pointer 교체를 한 번에 (D-5). pointer 교체는 listener가 아니라 이 step 안에서 한다.
     */
    @Bean(PUBLISH_STEP)
    public Step publishStep() {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(publishTasklet, transactionManager)
            .build();
    }
}
