package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.RowChecksum;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실험 3: 점수 계산 Step을 chunk(상품 ID reader → 합계 processor → 덮어쓰기 writer)로 할지,
 * GROUP BY 한 문장 tasklet으로 할지, chunk 크기는 얼마로 할지 비교한다.
 * 입력은 snapshot 입력 100,000행(상품 10,000개, 상품당 10행). 로컬 PC 부담 때문에 10만 행 안으로 둔다.
 * 결과는 docs/week9/experiments.md 실험 3.
 * 실행: {@code ./gradlew :apps:commerce-batch:experimentTest --tests '*ScoreStepStrategyExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
class ScoreStepStrategyExperimentTest {
    private static final String INPUT = "exp-score-input";
    private static final int PRODUCTS = 10_000;
    private static final int ROWS_PER_PRODUCT = 10;
    private static final int FAIL_AT_PRODUCT_INDEX = 9_000;  // 실패 실험: 9,000번째 상품 처리 직전

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private int run;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS exp_stage (
              variant VARCHAR(64) NOT NULL,
              product_id BIGINT NOT NULL,
              score BIGINT NOT NULL,
              PRIMARY KEY (variant, product_id)
            ) ENGINE=InnoDB
            """);
        jdbc.update("DELETE FROM exp_stage");
        jdbc.update("DELETE FROM ranking_snapshot_input WHERE snapshot_id = ?", INPUT);
        List<Object[]> rows = new ArrayList<>();
        long orderId = 1;
        for (int round = 0; round < ROWS_PER_PRODUCT; round++) {
            for (long productId = 1; productId <= PRODUCTS; productId++) {
                rows.add(new Object[] {INPUT, orderId++, productId, (int) (productId % 7 + 1), "2026-08-17 01:00:00"});
                if (rows.size() == 5_000) {
                    jdbc.batchUpdate("INSERT INTO ranking_snapshot_input VALUES (?, ?, ?, ?, ?)", rows);
                    rows.clear();
                }
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO ranking_snapshot_input VALUES (?, ?, ?, ?, ?)", rows);
        }
    }

    @Test
    void compareChunkSizesAndGroupByTasklet() throws Exception {
        log("입력 %d행, 상품 %d개", PRODUCTS * ROWS_PER_PRODUCT, PRODUCTS);

        // 1. 끝까지 한 번에: 시간과 결과 checksum
        String expected = null;
        for (int round = 1; round <= 2; round++) {
            for (int chunk : new int[] {10, 100, 1_000}) {
                String variant = "chunk" + chunk + "-" + round;
                long started = System.nanoTime();
                JobExecution execution = launch(chunkJob(variant, chunk, null));
                long elapsed = millisSince(started);
                String checksum = checksum(variant);
                expected = expected == null ? checksum : expected;
                log("[한 번에] chunk %d 회차 %d: %s %d ms, commit %d, checksum %s", chunk, round, execution.getStatus(), elapsed,
                    commits(execution), checksum.substring(0, 8));
                assertThat(checksum).isEqualTo(expected);
            }
            String variant = "groupby-" + round;
            long started = System.nanoTime();
            JobExecution execution = launch(groupByJob(variant, null));
            long elapsed = millisSince(started);
            log("[한 번에] GROUP BY tasklet 회차 %d: %s %d ms, checksum %s", round, execution.getStatus(), elapsed,
                checksum(variant).substring(0, 8));
            assertThat(checksum(variant)).isEqualTo(expected);
        }

        // 2. 9,000번째 상품 직전(또는 tasklet은 집계를 마친 뒤 commit 전)에 한 번 실패 → 같은 parameter로 다시 실행
        for (int chunk : new int[] {10, 100, 1_000}) {
            String variant = "chunk" + chunk + "-restart";
            AtomicBoolean fail = new AtomicBoolean(true);
            JobParameters params = params(variant);
            long started = System.nanoTime();
            JobExecution first = jobLauncher.run(chunkJob(variant, chunk, fail), params);
            long firstMillis = millisSince(started);
            started = System.nanoTime();
            JobExecution second = jobLauncher.run(chunkJob(variant, chunk, fail), params);
            long secondMillis = millisSince(started);
            log("[실패 뒤] chunk %d: 첫 실행 %s %d ms(실패 전 저장 %d개) → 재시작 %s %d ms(읽기 %d개), checksum 같음=%s", chunk,
                first.getStatus(), firstMillis, stageRowsBeforeRestart(first), second.getStatus(), secondMillis,
                readCount(second), checksum(variant).equals(expected));
        }
        String variant = "groupby-restart";
        AtomicBoolean fail = new AtomicBoolean(true);
        JobParameters params = params(variant);
        long started = System.nanoTime();
        JobExecution first = jobLauncher.run(groupByJob(variant, fail), params);
        long firstMillis = millisSince(started);
        started = System.nanoTime();
        JobExecution second = jobLauncher.run(groupByJob(variant, fail), params);
        long secondMillis = millisSince(started);
        log("[실패 뒤] GROUP BY tasklet: 첫 실행 %s %d ms(남은 점수 %d개) → 다시 실행 %s %d ms(처음부터), checksum 같음=%s",
            first.getStatus(), firstMillis, 0, second.getStatus(), secondMillis, checksum(variant).equals(expected));
    }

    /** 집계 Cursor 채택 전 점수 계산 Step과 같은 구성: 상품 ID keyset paging reader → 합계 processor → 덮어쓰기 writer. */
    private Job chunkJob(String variant, int chunk, AtomicBoolean failOnce) throws Exception {
        var reader = new JdbcPagingItemReaderBuilder<Long>()
            .name("expProductReader")
            .dataSource(dataSource)
            .selectClause("DISTINCT product_id")
            .fromClause("ranking_snapshot_input")
            .whereClause("snapshot_id = :snapshotId")
            .sortKeys(Map.of("product_id", Order.ASCENDING))
            .parameterValues(Map.of("snapshotId", INPUT))
            .pageSize(chunk)
            .rowMapper((rs, n) -> rs.getLong("product_id"))
            .saveState(true)
            .build();
        reader.afterPropertiesSet();  // 빈이 아니라 직접 만들었으므로 Spring 대신 초기화한다(실제 job은 @Bean이라 자동)
        AtomicInteger processed = new AtomicInteger();
        ItemProcessor<Long, long[]> processor = productId -> {
            if (failOnce != null && processed.incrementAndGet() == FAIL_AT_PRODUCT_INDEX && failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("9,000번째 상품 직전 실패");
            }
            return new long[] {productId, PurchaseActivityFixture.snapshotQuantity(jdbc, INPUT, productId)};
        };
        Step step = new StepBuilder("expScoreChunk", jobRepository)
            .<Long, long[]>chunk(chunk, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(items -> {
                List<Object[]> args = new ArrayList<>();
                for (long[] item : items) {
                    args.add(new Object[] {variant, item[0], item[1]});
                }
                jdbc.batchUpdate("INSERT INTO exp_stage VALUES (?, ?, ?) AS n ON DUPLICATE KEY UPDATE score = n.score", args);
            })
            .build();
        return new JobBuilder("expScoreChunkJob", jobRepository).start(step).build();
    }

    /** 한 문장으로 상품별 합계를 저장하는 tasklet. failOnce면 집계를 마친 뒤 commit 전에 한 번 실패한다. */
    private Job groupByJob(String variant, AtomicBoolean failOnce) {
        Step step = new StepBuilder("expScoreGroupBy", jobRepository)
            .tasklet((contribution, context) -> {
                jdbc.update("""
                    INSERT INTO exp_stage (variant, product_id, score)
                    SELECT ?, product_id, SUM(quantity) FROM ranking_snapshot_input WHERE snapshot_id = ? GROUP BY product_id
                    """, variant, INPUT);
                if (failOnce != null && failOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("집계 뒤 commit 전 실패");
                }
                return RepeatStatus.FINISHED;
            }, transactionManager)
            .build();
        return new JobBuilder("expScoreGroupByJob", jobRepository).start(step).build();
    }

    private JobExecution launch(Job job) throws Exception {
        return jobLauncher.run(job, params("run-" + (++run) + "-" + System.nanoTime()));
    }

    private static JobParameters params(String key) {
        return new JobParametersBuilder().addString("variant", key, true).toJobParameters();
    }

    private String checksum(String variant) {
        RowChecksum checksum = new RowChecksum();
        jdbc.query("SELECT product_id, score FROM exp_stage WHERE variant = ? ORDER BY product_id",
            rs -> {
                checksum.addRow(rs.getLong("product_id"), rs.getLong("score"));
            }, variant);
        return checksum.hex();
    }

    private long stageRowsBeforeRestart(JobExecution first) {
        return first.getStepExecutions().iterator().next().getWriteCount();
    }

    private long readCount(JobExecution execution) {
        return execution.getStepExecutions().iterator().next().getReadCount();
    }

    private long commits(JobExecution execution) {
        return execution.getStepExecutions().iterator().next().getCommitCount();
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void log(String format, Object... args) {
        System.out.println("[score-exp] " + String.format(format, args));
    }
}
