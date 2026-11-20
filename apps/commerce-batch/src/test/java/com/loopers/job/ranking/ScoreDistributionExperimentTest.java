package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=100"
})
class ScoreDistributionExperimentTest {
    private static final int PURCHASES = 100_000;
    private static final int CHUNK = 100;

    private enum Distribution {
        UNIQUE, EVEN, HOT;

        long product(int order) {
            return switch (this) {
                case UNIQUE -> order + 1;
                case EVEN -> 1 + order % 1_000;
                case HOT -> order % 10 == 9 ? 2 + (order / 10) % 999 : 1;
            };
        }
    }

    private record RankedScore(long product, long score, long rank) {}

    // 이 클래스의 측정은 상품별 SUM에 인덱스를 지정하기 전이다. 실제 Step의 SUM도 이 SQL로 바꿔 실행하고, 실행 계획도 이 SQL로 본다.
    private static final String PRODUCT_SUM = """
        SELECT COALESCE(SUM(quantity), 0) FROM ranking_snapshot_input WHERE snapshot_id = ? AND product_id = ?
        """;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;
    @Autowired
    private JobRepository repository;
    @Autowired
    private JobLauncher launcher;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    @Qualifier(WeeklyRankingJobConfig.SCORE_STEP)
    private Step productScoreStep;
    @MockitoSpyBean
    private WeeklyRankingStore store;

    @Test
    void comparesBothScoreStepsOnThreePurchaseDistributions() throws Exception {
        var jdbc = new JdbcTemplate(dataSource);
        var stats = new JdbcTemplate(new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test"));
        stats.update("UPDATE performance_schema.setup_consumers SET enabled = 'YES' WHERE name = 'events_statements_cpu'");
        var rowExperiment = new PurchaseRowScoreExperiment(dataSource, repository, transactions, jdbc);
        rowExperiment.prepareTables();
        var productJob = new JobBuilder("experimentProductScores", repository).start(productScoreStep).build();
        var sumJdbc = new JdbcTemplate(dataSource);
        doAnswer(invocation -> sumJdbc.queryForObject(PRODUCT_SUM, Long.class, invocation.getArgument(0), invocation.getArgument(1)))
            .when(store).productQuantityTotal(anyString(), anyLong());
        System.out.println("DISTRIBUTION MySQL=" + jdbc.queryForObject("SELECT VERSION()", String.class));
        for (int round = 1; round <= 2; round++) {
            for (Distribution distribution : Distribution.values()) {
                String snapshot = "dist-" + distribution + "-" + round;
                seed(jdbc, snapshot, distribution::product);
                var expected = ranking(jdbc, "SELECT product_id, SUM(quantity) AS score FROM ranking_snapshot_input "
                    + "WHERE snapshot_id = ? GROUP BY product_id", snapshot);
                int products = expected.size();
                assertThat(products).isEqualTo(distribution == Distribution.UNIQUE ? PURCHASES : 1_000);
                var rowJob = rowExperiment.job(snapshot, CHUNK, false);
                for (boolean rowBased : (round == 1 ? List.of(true, false) : List.of(false, true))) {
                    String table = rowBased ? "exp_purchase_row_score" : "ranking_score_stage";
                    var before = statementCounts(stats);
                    var loadBefore = DbLoad.read(stats);
                    var params = new JobParametersBuilder().addString("snapshotId", snapshot).toJobParameters();
                    var execution = launcher.run(rowBased ? rowJob : productJob, params);
                    var loadAfter = DbLoad.read(stats);
                    var after = statementCounts(stats);
                    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                    var step = execution.getStepExecutions().iterator().next();
                    long elapsed = Duration.between(step.getStartTime(), step.getEndTime()).toMillis();
                    for (int i = 0; i < after.length; i++) {
                        after[i] -= before[i];
                    }
                    System.out.printf("DISTRIBUTION %s %s ms=%d read=%d commits=%d pages=%d sums=%d ledger=%d scoreSQL=%d%n",
                        snapshot, rowBased ? "row" : "product", elapsed, step.getReadCount(), step.getCommitCount(),
                        after[0], after[1], after[2], after[3]);
                    loadAfter.reportDelta(loadBefore, snapshot, rowBased ? "row" : "product");
                    assertThat(step.getReadCount()).isEqualTo(rowBased ? PURCHASES : products);
                    assertThat(after[0]).isEqualTo(step.getReadCount() / CHUNK + 1);
                    assertThat(after[1]).isEqualTo(rowBased ? 0 : products);
                    assertThat(after[2]).isZero();
                    assertThat(after[3]).isPositive();
                    assertThat(ranking(jdbc, "SELECT product_id, score FROM " + table + " WHERE snapshot_id = ?", snapshot))
                        .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void measuresOneSumForSmallAndHotProducts() {
        String snapshot = "single-sum-100k";
        var jdbc = new JdbcTemplate(dataSource);
        // 매 열 번째 행에서 100행은 상품 2, 나머지 9,900행은 998개 상품에 배분한다.
        seed(jdbc, snapshot, order -> {
            if (order % 10 != 9) {
                return 1;
            }
            int other = order / 10;
            return other < 100 ? 2 : 3 + (other - 100) % 998;
        });

        measureProductSums(snapshot, jdbc, "after-load");
        jdbc.queryForList("ANALYZE TABLE ranking_snapshot_input");
        measureProductSums(snapshot, jdbc, "after-analyze");
    }

    private void measureProductSums(String snapshot, JdbcTemplate jdbc, String phase) {
        List<Long> products = List.of(2L, 1L);
        // JDBC 왕복과 결과 변환까지 측정한다. Chunk 안에서처럼 같은 트랜잭션의 연결을 쓴다.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int round = 0; round < 26; round++) {
                for (int slot = 0; slot < products.size(); slot++) {
                    long product = products.get((round + slot) % products.size());
                    long start = System.nanoTime();
                    long total = jdbc.queryForObject(PRODUCT_SUM, Long.class, snapshot, product);
                    long elapsed = System.nanoTime() - start;
                    assertThat(total).isEqualTo(product == 1 ? 359_995L : 401L);
                    if (round >= 5) {
                        System.out.printf("SINGLE_SUM phase=%s round=%d product=%d ns=%d%n", phase, round - 4, product, elapsed);
                    }
                }
            }
        });

        for (long product : products) {
            var plan = jdbc.queryForObject("EXPLAIN ANALYZE " + PRODUCT_SUM, String.class, snapshot, product);
            System.out.printf("SINGLE_SUM_PLAN phase=%s product=%d%n%s%n", phase, product, plan);
        }
    }

    private static void seed(JdbcTemplate jdbc, String snapshot, IntToLongFunction productForOrder) {
        var rows = new ArrayList<Object[]>();
        for (int order = 0; order < PURCHASES; order++) {
            rows.add(new Object[] {snapshot, order + 1, productForOrder.applyAsLong(order), 1 + order % 7});
            if (rows.size() == 1_000) {
                jdbc.batchUpdate("""
                    INSERT INTO ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
                    VALUES (?, ?, ?, ?, '2026-08-17 01:00:00')
                    """, rows);
                rows.clear();
            }
        }
    }

    private static List<RankedScore> ranking(JdbcTemplate jdbc, String scores, String snapshot) {
        return jdbc.query("SELECT product_id, score, ROW_NUMBER() OVER (ORDER BY score DESC, product_id) AS r "
            + "FROM (" + scores + ") totals ORDER BY r",
            (rs, n) -> new RankedScore(rs.getLong(1), rs.getLong(2), rs.getLong(3)), snapshot);
    }

    private record DbLoad(long examined, long cpuPs, long bufferReads, long redoBytes) {
        static DbLoad read(JdbcTemplate stats) {
            return stats.queryForObject("""
                SELECT COALESCE(SUM(sum_rows_examined), 0), COALESCE(SUM(sum_cpu_time), 0),
                    (SELECT variable_value FROM performance_schema.global_status WHERE variable_name = 'Innodb_buffer_pool_reads'),
                    (SELECT variable_value FROM performance_schema.global_status WHERE variable_name = 'Innodb_os_log_written')
                FROM performance_schema.events_statements_summary_by_digest
                WHERE schema_name = DATABASE() AND (
                    (digest_text LIKE 'SELECT %' AND digest_text LIKE '%FROM `ranking_snapshot_input`%'
                        AND (digest_text LIKE '%LIMIT%' OR digest_text LIKE '%COALESCE%'))
                    OR digest_text LIKE 'INSERT INTO `exp_purchase_row_score`%'
                    OR digest_text LIKE 'INSERT INTO `ranking_score_stage`%'
                )
                """, (rs, n) -> new DbLoad(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        }

        void reportDelta(DbLoad before, String snapshot, String strategy) {
            long rows = examined - before.examined;
            long cpu = cpuPs - before.cpuPs;
            assertThat(rows).isPositive();
            assertThat(cpu).isPositive();
            System.out.printf(Locale.ROOT, "DB_LOAD %s %s rowsExamined=%d sqlCpuMs=%.3f bufferPoolReads=%d redoBytes=%d%n",
                snapshot, strategy, rows, cpu / 1_000_000_000.0, bufferReads - before.bufferReads, redoBytes - before.redoBytes);
        }
    }

    // 전용 MySQL 컨테이너에서 Step 실행 전후의 서버 SQL 실행 횟수 차이를 구한다.
    private static long[] statementCounts(JdbcTemplate stats) {
        long[] counts = new long[4];
        stats.query("""
            SELECT digest_text, count_star FROM performance_schema.events_statements_summary_by_digest
            WHERE schema_name = DATABASE() AND digest_text IS NOT NULL
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String sql = rs.getString(1).replace("`", "").toLowerCase(Locale.ROOT);
                long calls = rs.getLong(2);
                if (sql.startsWith("select") && sql.contains("from ranking_snapshot_input")) {
                    if (sql.contains("coalesce")) {
                        counts[1] += calls;
                    } else if (sql.contains("limit")) {
                        counts[0] += calls;
                    }
                } else if (sql.startsWith("insert into exp_purchase_row_applied")) {
                    counts[2] += calls;
                } else if (sql.startsWith("insert into exp_purchase_row_score") || sql.startsWith("insert into ranking_score_stage")) {
                    counts[3] += calls;
                }
            });
        return counts;
    }
}
