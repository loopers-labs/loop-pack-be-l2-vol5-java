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

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 실험 13: 입력을 대량으로 넣은 직후(실제 배치처럼 입력 고정 바로 뒤) 점수 계산을 시작하면
 * 테이블 통계 상태에 따라 실행 계획과 DB가 검사한 행이 달라지는가.
 * 통계 상태 3가지 × 구매 분포 3가지 × 읽는 단위 2가지(구매 행 / 상품 ID) × 3회. 이번 주 입력은 5만 행이다.
 * 실험 13·14는 인덱스 지정 전 측정이라 실제 Step의 상품별 SUM을 힌트 없는 {@link #PRODUCT_SUM}으로 바꿔 실행하고,
 * 실행 계획도 같은 SQL로 본다. 실험 15는 실제 Step의 SQL(상품 인덱스 지정)을 그대로 쓴다.
 * 결과는 docs/week9/experiments.md 실험 13.
 * 실행: {@code ./gradlew :apps:commerce-batch:experimentTest --tests '*StatsFreshnessExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=100"
})
class StatsFreshnessExperimentTest {
    // 로컬 부담 때문에 테이블 전체를 10만 행 안으로 둔다: 이번 주 5만 행(+ 누적 조건은 이전 주 5만 행).
    private static final int PURCHASES = 50_000;
    private static final int CHUNK = 100;
    private static final int REPEAT = 3;

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

    /** 점수 계산 직전의 통계 상태. */
    private enum Stats {
        /** 빈 테이블에 이번 입력만 넣은 직후. */
        FRESH_EMPTY,
        /** 이번 입력을 넣고 ANALYZE TABLE을 실행한 뒤. */
        ANALYZED,
        /** 이전 주 입력 5만 행이 이미 있고 통계도 그때 갱신된 테이블에 이번 입력을 넣은 직후. */
        FRESH_ON_PREVIOUS_WEEK
    }

    // 구매 행 Reader(MySqlPagingQueryProvider, 정렬 키 order_id·product_id)가 두 번째 페이지부터 보내는 SQL과 같은 모양
    private static final String ROW_NEXT_PAGE = """
        SELECT order_id, product_id, quantity FROM ranking_snapshot_input
        WHERE (snapshot_id = ?) AND ((order_id > ?) OR (order_id = ? AND product_id > ?))
        ORDER BY order_id ASC, product_id ASC LIMIT 100
        """;
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

    private JdbcTemplate jdbc;
    private JdbcTemplate root;
    private int run;

    @Test
    void comparesPlansRightAfterLoadAndAfterAnalyze() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        root = new JdbcTemplate(new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test"));
        var rowExperiment = new PurchaseRowScoreExperiment(dataSource, repository, transactions, jdbc);
        rowExperiment.prepareTables();
        sumWithoutIndexHint();
        var productJob = new JobBuilder("experimentStatsProductScores", repository).start(productScoreStep).build();
        log("MySQL=%s innodb_stats_persistent=%s innodb_stats_auto_recalc=%s",
            root.queryForObject("SELECT VERSION()", String.class),
            root.queryForObject("SELECT @@innodb_stats_persistent", String.class),
            root.queryForObject("SELECT @@innodb_stats_auto_recalc", String.class));

        for (int round = 1; round <= REPEAT; round++) {
            for (Distribution distribution : Distribution.values()) {
                for (Stats stats : Stats.values()) {
                    // 회차마다 먼저 실행하는 방식을 바꾼다.
                    for (boolean rowBased : (round % 2 == 1 ? List.of(true, false) : List.of(false, true))) {
                        measure(rowExperiment, productJob, distribution, stats, rowBased, round);
                    }
                }
            }
        }
    }

    private void measure(PurchaseRowScoreExperiment rowExperiment, org.springframework.batch.core.Job productJob,
                         Distribution distribution, Stats stats, boolean rowBased, int round) throws Exception {
        String snapshot = "stats-" + (++run);
        root.execute("TRUNCATE TABLE loopers.ranking_snapshot_input");
        if (stats == Stats.FRESH_ON_PREVIOUS_WEEK) {
            seed("stats-prev-" + run, distribution::product);
            analyze();
        }
        seed(snapshot, distribution::product);
        if (stats == Stats.ANALYZED) {
            analyze();
        }
        String label = String.format("%s %s %s round=%d", distribution, stats, rowBased ? "row" : "product", round);
        log("[통계] %s 시작 전: %s", label, tableStats());
        log("[계획] %s 시작 전: %s", label, plan(rowBased, distribution, snapshot));

        long examinedBefore = examined();
        var params = new JobParametersBuilder().addString("snapshotId", snapshot).toJobParameters();
        var execution = launcher.run(rowBased ? rowExperiment.job(snapshot, CHUNK, false) : productJob, params);
        long examined = examined() - examinedBefore;
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var step = execution.getStepExecutions().iterator().next();
        long elapsed = Duration.between(step.getStartTime(), step.getEndTime()).toMillis();

        String table = rowBased ? "exp_purchase_row_score" : "ranking_score_stage";
        assertThat(scores("SELECT product_id, score FROM " + table + " WHERE snapshot_id = ? ORDER BY product_id", snapshot))
            .isEqualTo(scores("SELECT product_id, SUM(quantity) FROM ranking_snapshot_input "
                + "WHERE snapshot_id = ? GROUP BY product_id ORDER BY product_id", snapshot));
        log("[결과] %s: Step %d ms, 읽은 item %d, 검사한 행 %d, 결과 일치", label, elapsed, step.getReadCount(), examined);
        log("[통계] %s 끝난 뒤: %s", label, tableStats());
        log("[계획] %s 끝난 뒤: %s", label, plan(rowBased, distribution, snapshot));
    }

    /**
     * 실험 14: 통계를 갱신한 상태에서 이번 주 입력을 2.5만·5만·10만 행으로 바꾸면 Step 시간이 어떻게 늘어나는가.
     * 분포 규칙은 같다(상품 수가 고정인 분포는 상품당 행 수가 늘고, 상품마다 1행인 분포는 상품 수가 는다). 테이블에는 한 크기만 둔다.
     */
    @Test
    void comparesInputSizesAfterAnalyze() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        root = new JdbcTemplate(new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test"));
        var rowExperiment = new PurchaseRowScoreExperiment(dataSource, repository, transactions, jdbc);
        rowExperiment.prepareTables();
        sumWithoutIndexHint();
        var productJob = new JobBuilder("experimentSizeProductScores", repository).start(productScoreStep).build();
        for (int round = 1; round <= REPEAT; round++) {
            for (int size : new int[] {25_000, 50_000, 100_000}) {
                for (Distribution distribution : Distribution.values()) {
                    for (boolean rowBased : (round % 2 == 1 ? List.of(true, false) : List.of(false, true))) {
                        String snapshot = "size-" + (++run);
                        root.execute("TRUNCATE TABLE loopers.ranking_snapshot_input");
                        seed(snapshot, distribution::product, size);
                        analyze();
                        long examinedBefore = examined();
                        var params = new JobParametersBuilder().addString("snapshotId", snapshot).toJobParameters();
                        var execution = launcher.run(rowBased ? rowExperiment.job(snapshot, CHUNK, false) : productJob, params);
                        long examined = examined() - examinedBefore;
                        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                        var step = execution.getStepExecutions().iterator().next();
                        long elapsed = Duration.between(step.getStartTime(), step.getEndTime()).toMillis();
                        String table = rowBased ? "exp_purchase_row_score" : "ranking_score_stage";
                        assertThat(scores("SELECT product_id, score FROM " + table + " WHERE snapshot_id = ? ORDER BY product_id", snapshot))
                            .isEqualTo(scores("SELECT product_id, SUM(quantity) FROM ranking_snapshot_input "
                                + "WHERE snapshot_id = ? GROUP BY product_id ORDER BY product_id", snapshot));
                        log("[규모] %s size=%d %s round=%d: Step %d ms, 읽은 item %d, 검사한 행 %d, 결과 일치", distribution, size,
                            rowBased ? "row" : "product", round, elapsed, step.getReadCount(), examined);
                    }
                }
            }
        }
    }

    /**
     * 실험 15: 인덱스를 지정한 뒤, 빈 테이블에 넣은 직후와 ANALYZE 뒤를 다시 잰다.
     * 상품 ID 방식은 실제 Step(상품별 SUM에 상품 인덱스 지정), 구매 행 방식은 다음 페이지 SQL에 PRIMARY 지정.
     */
    @Test
    void comparesAfterIndexHint() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        root = new JdbcTemplate(new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test"));
        var rowExperiment = new PurchaseRowScoreExperiment(dataSource, repository, transactions, jdbc);
        rowExperiment.prepareTables();
        var productJob = new JobBuilder("experimentHintProductScores", repository).start(productScoreStep).build();
        for (int round = 1; round <= REPEAT; round++) {
            for (Distribution distribution : Distribution.values()) {
                for (Stats stats : List.of(Stats.FRESH_EMPTY, Stats.ANALYZED)) {
                    for (boolean rowBased : (round % 2 == 1 ? List.of(true, false) : List.of(false, true))) {
                        String snapshot = "hint-" + (++run);
                        root.execute("TRUNCATE TABLE loopers.ranking_snapshot_input");
                        seed(snapshot, distribution::product);
                        if (stats == Stats.ANALYZED) {
                            analyze();
                        }
                        String label = String.format("%s %s %s round=%d", distribution, stats, rowBased ? "row" : "product", round);
                        log("[힌트 통계] %s 시작 전: %s", label, tableStats());
                        long examinedBefore = examined();
                        var params = new JobParametersBuilder().addString("snapshotId", snapshot).toJobParameters();
                        var execution = launcher.run(rowBased ? rowExperiment.job(snapshot, CHUNK, false, true) : productJob, params);
                        long examined = examined() - examinedBefore;
                        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                        var step = execution.getStepExecutions().iterator().next();
                        long elapsed = Duration.between(step.getStartTime(), step.getEndTime()).toMillis();
                        String table = rowBased ? "exp_purchase_row_score" : "ranking_score_stage";
                        assertThat(scores("SELECT product_id, score FROM " + table + " WHERE snapshot_id = ? ORDER BY product_id", snapshot))
                            .isEqualTo(scores("SELECT product_id, SUM(quantity) FROM ranking_snapshot_input "
                                + "WHERE snapshot_id = ? GROUP BY product_id ORDER BY product_id", snapshot));
                        log("[힌트 결과] %s: Step %d ms, 읽은 item %d, 검사한 행 %d, 결과 일치", label, elapsed, step.getReadCount(), examined);
                    }
                }
            }
        }
    }

    /** 인덱스 지정 전 측정: 실제 Step의 상품별 SUM을 힌트 없는 SQL로 실행한다. */
    private void sumWithoutIndexHint() {
        doAnswer(invocation -> jdbc.queryForObject(PRODUCT_SUM, Long.class, invocation.getArgument(0), invocation.getArgument(1)))
            .when(store).productQuantityTotal(anyString(), anyLong());
    }

    /** 실제 Step이 보내는 것과 같은 모양의 SQL을 EXPLAIN한다. 구매 행은 중간 페이지, 상품 ID는 상품 하나의 SUM. */
    private String plan(boolean rowBased, Distribution distribution, String snapshot) {
        if (rowBased) {
            return explain(ROW_NEXT_PAGE, snapshot, PURCHASES / 2, PURCHASES / 2, Long.MAX_VALUE);
        }
        long product = distribution == Distribution.UNIQUE ? PURCHASES / 2 : 500;
        String plan = "SUM(상품 " + product + ") " + explain(PRODUCT_SUM, snapshot, product);
        if (distribution == Distribution.HOT) {
            plan += " / SUM(쏠린 상품 1) " + explain(PRODUCT_SUM, snapshot, 1L);
        }
        return plan;
    }

    private String explain(String sql, Object... args) {
        return String.join(" | ", jdbc.query("EXPLAIN " + sql, (rs, n) -> String.format("type=%s key=%s rows=%s extra=%s",
            rs.getString("type"), rs.getString("key"), rs.getString("rows"), rs.getString("Extra")), args));
    }

    private String tableStats() {
        Long rows = root.queryForObject("""
            SELECT n_rows FROM mysql.innodb_table_stats WHERE database_name = 'loopers' AND table_name = 'ranking_snapshot_input'
            """, Long.class);
        Long actual = jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot_input", Long.class);
        return "통계 n_rows=" + rows + ", 실제 행=" + actual;
    }

    private List<String> scores(String sql, String snapshot) {
        return jdbc.query(sql, (rs, n) -> rs.getLong(1) + ":" + rs.getLong(2), snapshot);
    }

    private void analyze() {
        jdbc.queryForList("ANALYZE TABLE ranking_snapshot_input");
    }

    /** 점수 계산 SQL(구매 행 페이지·상품 ID 페이지·상품별 SUM)이 검사한 행의 누적값. */
    private long examined() {
        return root.queryForObject("""
            SELECT COALESCE(SUM(sum_rows_examined), 0) FROM performance_schema.events_statements_summary_by_digest
            WHERE schema_name = 'loopers' AND digest_text LIKE 'SELECT %' AND digest_text LIKE '%FROM `ranking_snapshot_input`%'
              AND (digest_text LIKE '%LIMIT%' OR digest_text LIKE '%COALESCE%')
            """, Long.class);
    }

    private void seed(String snapshot, IntToLongFunction productForOrder) {
        seed(snapshot, productForOrder, PURCHASES);
    }

    private void seed(String snapshot, IntToLongFunction productForOrder, int purchases) {
        var rows = new ArrayList<Object[]>();
        for (int order = 0; order < purchases; order++) {
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

    private static void log(String format, Object... args) {
        System.out.println("[stats-exp] " + String.format(format, args));
    }
}
