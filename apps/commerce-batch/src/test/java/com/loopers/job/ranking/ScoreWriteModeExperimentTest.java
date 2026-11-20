package com.loopers.job.ranking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 점수 쓰기 방식 비교 실험: 절대값({@code score = total})과 증분({@code score = score + delta}).
 * 상품 7·8·9(점수 5·1·5)를 chunk=2로 쓴다. 첫 chunk(7·8)는 commit되고, 두 번째 chunk(9)를 쓴 직후 한 번 실패시킨 뒤 다시 실행한다.
 * <ul>
 *   <li>SAME_TX: 점수 쓰기와 reader 위치가 같은 chunk TX(현재 구조). 같은 parameter로 재시작</li>
 *   <li>SEPARATE_TX: 점수 쓰기만 별도 TX로 먼저 commit. 같은 parameter로 재시작</li>
 *   <li>NEW_INSTANCE: 같은 chunk TX지만, 실패 뒤 run.id를 붙여 새 JobInstance로 처음부터 실행</li>
 * </ul>
 * 결과는 docs/week9/experiments.md 실험 2.
 * 실행: {@code ./gradlew :apps:commerce-batch:experimentTest --tests '*ScoreWriteModeExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class ScoreWriteModeExperimentTest {

    private enum Write {
        ABSOLUTE("INSERT INTO exp_score VALUES (?, ?, ?) AS n ON DUPLICATE KEY UPDATE score = n.score"),
        INCREMENT("INSERT INTO exp_score VALUES (?, ?, ?) AS n ON DUPLICATE KEY UPDATE score = exp_score.score + n.score");

        private final String sql;

        Write(String sql) {
            this.sql = sql;
        }
    }

    private enum Recovery { SAME_TX, SEPARATE_TX, NEW_INSTANCE }

    private record ProductTotal(long productId, long total) {}

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

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS exp_score (
              run_key VARCHAR(64) NOT NULL,
              product_id BIGINT NOT NULL,
              score BIGINT NOT NULL,
              PRIMARY KEY (run_key, product_id)
            ) ENGINE=InnoDB
            """);
        jdbc.update("DELETE FROM ranking_snapshot_input WHERE snapshot_id = 'exp-write'");
        jdbc.batchUpdate("INSERT INTO ranking_snapshot_input VALUES ('exp-write', ?, ?, ?, '2026-08-17 01:00:00')", List.of(
            new Object[] {42L, 7L, 2}, new Object[] {42L, 8L, 1}, new Object[] {43L, 7L, 3}, new Object[] {44L, 9L, 5}));
    }

    @Test
    void compareAbsoluteAndIncrementWrites() throws Exception {
        for (Recovery recovery : Recovery.values()) {
            for (Write write : Write.values()) {
                String runKey = recovery + "-" + write;
                JobParameters params = new JobParametersBuilder().addString("runKey", runKey, true).toJobParameters();

                JobExecution first = jobLauncher.run(job(runKey, write, recovery, true), params);
                Map<Long, Long> afterFailure = scores(runKey);

                JobParameters retry = recovery == Recovery.NEW_INSTANCE
                    ? new JobParametersBuilder(params).addLong("run.id", 2L, true).toJobParameters()
                    : params;
                JobExecution second = jobLauncher.run(job(runKey, write, recovery, false), retry);

                System.out.printf("[write-exp] %-12s %-9s 첫 실행=%s 실패 직후 점수=%s → 다시 실행=%s(%s instance) 최종 점수=%s%n",
                    recovery, write, first.getStatus(), afterFailure, second.getStatus(),
                    second.getJobInstance().getInstanceId() == first.getJobInstance().getInstanceId() ? "같은" : "새",
                    scores(runKey));
            }
        }
    }

    private Job job(String runKey, Write write, Recovery recovery, boolean failOnceAtNine) {
        var reader = new JdbcCursorItemReaderBuilder<ProductTotal>()
            .name("expProductReader")
            .saveState(true)
            .dataSource(dataSource)
            .sql("SELECT product_id, SUM(quantity) AS total FROM ranking_snapshot_input "
                + "WHERE snapshot_id = 'exp-write' GROUP BY product_id ORDER BY product_id")
            .rowMapper((rs, n) -> new ProductTotal(rs.getLong("product_id"), rs.getLong("total")))
            .build();
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicBoolean failed = new AtomicBoolean(!failOnceAtNine);

        var step = new StepBuilder("expWriteScores", jobRepository)
            .<ProductTotal, ProductTotal>chunk(2, transactionManager)
            .reader(reader)
            .writer(items -> {
                Runnable write0 = () -> jdbc.batchUpdate(write.sql,
                    items.getItems().stream().map(i -> new Object[] {runKey, i.productId(), i.total()}).toList());
                if (recovery == Recovery.SEPARATE_TX) {
                    separate.executeWithoutResult(status -> write0.run());
                } else {
                    write0.run();
                }
            })
            // 상품 9의 점수를 쓴 직후, chunk TX가 commit(=reader 위치 저장)되기 전에 한 번 실패한다.
            .listener(new ItemWriteListener<ProductTotal>() {
                @Override
                public void afterWrite(Chunk<? extends ProductTotal> items) {
                    boolean hasNine = items.getItems().stream().anyMatch(i -> i.productId() == 9L);
                    if (hasNine && failed.compareAndSet(false, true)) {
                        throw new IllegalStateException("점수를 쓴 뒤 commit 전 실패");
                    }
                }
            })
            .build();
        return new JobBuilder("expWriteMode", jobRepository).start(step).build();
    }

    private Map<Long, Long> scores(String runKey) {
        Map<Long, Long> scores = new TreeMap<>();
        jdbc.query("SELECT product_id, score FROM exp_score WHERE run_key = ?",
            rs -> {
                scores.put(rs.getLong("product_id"), rs.getLong("score"));
            }, runKey);
        return scores;
    }
}
