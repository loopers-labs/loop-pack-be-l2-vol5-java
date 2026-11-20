package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.RowChecksum;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.item.database.support.SqlPagingQueryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "datasource.mysql-jpa.main.data-source-properties.useCursorFetch=true"
})
class RankingReaderStrategyExperimentTest {
    private static final String READER = "readerStrategy";
    private static final String TOTALS = "SELECT product_id, SUM(quantity) AS score FROM ranking_snapshot_input "
        + "WHERE snapshot_id = ? GROUP BY product_id ORDER BY product_id";

    private enum Strategy { PRODUCT_IDS, GROUPED_PAGING, GROUPED_CURSOR, GROUPED_RANGE_PAGING }

    private static final List<Strategy> ORIGINAL_STRATEGIES = List.of(
        Strategy.PRODUCT_IDS, Strategy.GROUPED_PAGING, Strategy.GROUPED_CURSOR);

    private record ReaderItem(long productId, Long score) {}

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WeeklyRankingStore store;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    @Test
    void eachReaderResumesAfterTheFirstCommittedChunk() throws Exception {
        String input = key();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.batchUpdate("""
            INSERT INTO ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
            VALUES (?, ?, ?, ?, '2026-08-17 01:00:00')
            """, List.of(new Object[] {input, 42, 7, 2}, new Object[] {input, 42, 8, 1},
            new Object[] {input, 43, 7, 3}, new Object[] {input, 44, 9, 5}));
        for (Strategy strategy : ORIGINAL_STRATEGIES) {
            String output = key();
            var params = parameters(output);
            var first = run(strategy, input, output, 2, 9, params, "fixture-failed");
            verifyRestart(first, strategy, output, 2, 8);
            var restarted = run(strategy, input, output, 2, 0, params, "fixture-restarted");
            verifyCompletion(first, restarted, 1);
            assertThat(ranking(output)).containsExactly("7:5:1", "9:5:2", "8:1:3");
        }
    }

    @Test
    void compareReadersOnTheSameFrozenInput() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var settings = dataSource.unwrap(HikariDataSource.class).getDataSourceProperties();
        assertThat(settings.getProperty("useCursorFetch")).isEqualTo("true");
        log("useCursorFetch=%s, rewriteBatchedStatements=%s", settings.get("useCursorFetch"), settings.get("rewriteBatchedStatements"));
        log("MySQL %s, driver=%s, Batch=%s", jdbc.queryForObject("SELECT VERSION()", String.class),
            jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDriverVersion()), JobExecution.class.getPackage().getImplementationVersion());
        for (int purchasesPerProduct : List.of(10, 20)) {
            int products = 10_000;
            int chunk = 100;
            String input = seed(jdbc, products, purchasesPerProduct);
            String expected = expectedChecksum(products, purchasesPerProduct);
            log("input=%d products=%d chunk=%d pageSize=%d fetchSize=%d",
                products * purchasesPerProduct, products, chunk, chunk, chunk);

            for (Strategy strategy : ORIGINAL_STRATEGIES) {
                measureCleanRun(strategy, input, expected, products, chunk, "warmup");
            }
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < ORIGINAL_STRATEGIES.size(); i++) {
                    Strategy strategy = ORIGINAL_STRATEGIES.get((round + i) % ORIGINAL_STRATEGIES.size());
                    measureCleanRun(strategy, input, expected, products, chunk, "round-" + (round + 1));
                }
            }
            for (Strategy strategy : List.of(Strategy.GROUPED_PAGING, Strategy.GROUPED_CURSOR)) {
                String output = key();
                var params = parameters(output);
                var first = run(strategy, input, output, chunk, 9_001, params, "failed");
                verifyRestart(first, strategy, output, 9_000, 9_000);
                var restarted = run(strategy, input, output, chunk, 0, params, "restarted");
                verifyCompletion(first, restarted, 1_000);
                assertThat(checksum(output)).isEqualTo(expected);
            }
            explain(jdbc, input, chunk);
        }
    }

    @Test
    void compareRangePagingWithCursor() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var named = new NamedParameterJdbcTemplate(jdbc);
        int products = 10_000;
        int chunk = 100;
        String input = seed(jdbc, products, 10);
        String expected = expectedChecksum(products, 10);
        var params = Map.of("snapshotId", input, "_product_id", 9_000);

        String generated = pagingProvider(Strategy.GROUPED_PAGING).generateRemainingPagesQuery(chunk);
        String range = pagingProvider(Strategy.GROUPED_RANGE_PAGING).generateRemainingPagesQuery(chunk);
        String withoutHint = range.replace(" FORCE INDEX (idx_ranking_snapshot_input_product)", "");
        var expectedPage = expectedPage(9_001, chunk);
        for (String query : List.of(generated, withoutHint, range)) {
            log("late-page SQL=%s%n%s", query, named.queryForObject("EXPLAIN ANALYZE " + query, params, String.class));
            var page = named.query(query, params, (rs, n) -> new ProductScore(rs.getLong("product_id"), rs.getLong("score")));
            assertThat(page).containsExactlyElementsOf(expectedPage);
        }

        var strategies = List.of(Strategy.GROUPED_RANGE_PAGING, Strategy.GROUPED_CURSOR);
        for (var strategy : strategies) {
            measureCleanRun(strategy, input, expected, products, chunk, "range-warmup");
        }
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < strategies.size(); i++) {
                var strategy = strategies.get((round + i) % strategies.size());
                measureCleanRun(strategy, input, expected, products, chunk, "range-round-" + (round + 1));
            }
        }
        for (var strategy : strategies) {
            String output = key();
            var jobParams = parameters(output);
            var first = run(strategy, input, output, chunk, 9_001, jobParams, "range-failed");
            verifyRestart(first, strategy, output, 9_000, 9_000);
            var restarted = run(strategy, input, output, chunk, 0, jobParams, "range-restarted");
            verifyCompletion(first, restarted, 1_000);
            assertThat(checksum(output)).isEqualTo(expected);
        }
    }

    @Test
    void observesConnectionAfterCommitAndSqlOnRestart() throws Exception {
        var source = dataSource.unwrap(HikariDataSource.class);
        var pool = source.getHikariPoolMXBean();
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(source.getJdbcUrl(), "root", "test"));
        String input = key();
        insertInputs(new JdbcTemplate(dataSource), List.of(
            new Object[] {input, 42, 7, 2}, new Object[] {input, 42, 8, 1},
            new Object[] {input, 43, 7, 3}, new Object[] {input, 44, 9, 5}));

        for (var strategy : List.of(Strategy.GROUPED_RANGE_PAGING, Strategy.GROUPED_CURSOR)) {
            String output = key();
            var params = parameters(output);
            List<Integer> activeAfterCommit = new ArrayList<>();
            int activeBefore = pool.getActiveConnections();
            var first = run(strategy, input, output, 2, 9, params, "observe-failed",
                () -> activeAfterCommit.add(pool.getActiveConnections() - activeBefore));
            verifyRestart(first, strategy, output, 2, 8);
            assertThat(pool.getActiveConnections()).isEqualTo(activeBefore);

            root.execute("TRUNCATE TABLE mysql.general_log");
            root.execute("SET GLOBAL log_output = 'TABLE'");
            root.execute("SET GLOBAL general_log = 'ON'");
            JobExecution resumed;
            try {
                resumed = run(strategy, input, output, 2, 0, params, "observe-restarted");
            } finally {
                root.execute("SET GLOBAL general_log = 'OFF'");
            }
            var sql = root.queryForList("""
                SELECT CONVERT(argument USING utf8mb4) FROM mysql.general_log
                WHERE command_type IN ('Query', 'Execute')
                  AND CONVERT(argument USING utf8mb4) LIKE 'SELECT product_id, SUM(quantity) AS score%'
                  AND CONVERT(argument USING utf8mb4) LIKE ?
                ORDER BY event_time
                """, String.class, "%'" + input + "'%");

            verifyCompletion(first, resumed, 1);
            assertThat(ranking(output)).containsExactly("7:5:1", "9:5:2", "8:1:3");
            assertThat(sql).isNotEmpty();
            if (strategy == Strategy.GROUPED_RANGE_PAGING) {
                assertThat(activeAfterCommit).containsExactly(0);
                assertThat(sql.getFirst()).containsPattern("product_id\\s*>\\s*8");
            } else {
                assertThat(activeAfterCommit).containsExactly(1);
                assertThat(sql.getFirst()).doesNotContain("product_id >");
            }
            log("%s active increase after commit=%s; active after Step=%d; restart SQL=%s",
                strategy, activeAfterCommit, pool.getActiveConnections() - activeBefore, sql);
        }
    }

    private static List<ProductScore> expectedPage(long first, int size) {
        List<ProductScore> expected = new ArrayList<>();
        for (long productId = first; productId < first + size; productId++) {
            expected.add(new ProductScore(productId, (productId % 7 + 1) * 10));
        }
        return expected;
    }

    private void measureCleanRun(Strategy strategy, String input, String expected, int products, int chunk, String phase) throws Exception {
        String output = key();
        var execution = run(strategy, input, output, chunk, 0, parameters(output), phase);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions().iterator().next().getReadCount()).isEqualTo(products);
        assertThat(checksum(output)).isEqualTo(expected);
    }

    private JobExecution run(Strategy strategy, String input, String output, int chunk, long failAt,
                             JobParameters params, String phase) throws Exception {
        return run(strategy, input, output, chunk, failAt, params, phase, null);
    }

    private JobExecution run(Strategy strategy, String input, String output, int chunk, long failAt,
                             JobParameters params, String phase, Runnable afterCommit) throws Exception {
        Measurements measurements = new Measurements();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var reader = new TimedReader(reader(strategy, input, chunk), measurements);
        var stepBuilder = new StepBuilder("readerStrategyScores", jobRepository)
            .<ReaderItem, ProductScore>chunk(chunk, transactionManager)
            .reader(reader)
            .processor(item -> {
                if (item.productId() == failAt) {
                    throw new IllegalStateException("상품 " + failAt + " 처리 직전 실패");
                }
                if (strategy != Strategy.PRODUCT_IDS) {
                    return new ProductScore(item.productId(), item.score());
                }
                long started = System.nanoTime();
                long score = PurchaseActivityFixture.snapshotQuantity(jdbc, input, item.productId());
                measurements.sumCalls++;
                measurements.sumNanos += System.nanoTime() - started;
                return new ProductScore(item.productId(), score);
            })
            .writer(items -> {
                long started = System.nanoTime();
                ScoreWriterBatchRewriteExperimentTest.upsertHistoricalScores(jdbc, output, List.copyOf(items.getItems()));
                measurements.writeCalls++;
                measurements.writeNanos += System.nanoTime() - started;
            });
        if (afterCommit != null) {
            stepBuilder.listener(new ChunkListener() {
                @Override
                public void afterChunk(ChunkContext context) {
                    afterCommit.run();
                }
            });
        }
        var job = new JobBuilder("rankingReaderStrategy", jobRepository).start(stepBuilder.build()).build();
        long started = System.nanoTime();
        JobExecution execution = jobLauncher.run(job, params);
        var result = execution.getStepExecutions().iterator().next();
        log("%s %s %s total=%dms open=%dms read=%dms readerQueries=%d sum=%dms sumQueries=%d writer=%dms batches=%d commit=%d items=%d",
            strategy, phase, execution.getStatus(), millis(System.nanoTime() - started),
            millis(measurements.openNanos), millis(measurements.readNanos), measurements.readQueries,
            millis(measurements.sumNanos), measurements.sumCalls,
            millis(measurements.writeNanos), measurements.writeCalls, result.getCommitCount(), result.getReadCount());
        return execution;
    }

    private ItemStreamReader<ReaderItem> reader(Strategy strategy, String input, int chunk) throws Exception {
        if (strategy == Strategy.GROUPED_CURSOR) {
            var cursor = new JdbcCursorItemReaderBuilder<ReaderItem>()
                .name(READER).dataSource(dataSource).sql(TOTALS).preparedStatementSetter(ps -> ps.setString(1, input))
                .rowMapper((rs, n) -> new ReaderItem(rs.getLong("product_id"), rs.getLong("score")))
                .fetchSize(chunk).saveState(true).build();
            cursor.afterPropertiesSet();
            return cursor;
        }
        boolean grouped = strategy != Strategy.PRODUCT_IDS;
        var paging = new JdbcPagingItemReaderBuilder<ReaderItem>()
            .name(READER).dataSource(dataSource).queryProvider(pagingProvider(strategy))
            .parameterValues(Map.of("snapshotId", input)).pageSize(chunk).saveState(true)
            .rowMapper((rs, n) -> new ReaderItem(rs.getLong("product_id"), grouped ? rs.getLong("score") : null))
            .build();
        paging.afterPropertiesSet();
        return paging;
    }

    private MySqlPagingQueryProvider pagingProvider(Strategy strategy) throws Exception {
        boolean grouped = strategy != Strategy.PRODUCT_IDS;
        boolean directRange = strategy == Strategy.GROUPED_RANGE_PAGING;
        var provider = directRange ? new MySqlPagingQueryProvider() {
            @Override
            public String generateRemainingPagesQuery(int pageSize) {
                return SqlPagingQueryUtils.generateLimitSqlQuery(this, true, "LIMIT " + pageSize);
            }
        } : new MySqlPagingQueryProvider();
        provider.setSelectClause(grouped ? "product_id, SUM(quantity) AS score" : "DISTINCT product_id");
        provider.setFromClause(directRange
            ? "ranking_snapshot_input FORCE INDEX (idx_ranking_snapshot_input_product)" : "ranking_snapshot_input");
        provider.setWhereClause("snapshot_id = :snapshotId");
        provider.setSortKeys(Map.of("product_id", Order.ASCENDING));
        if (grouped) {
            provider.setGroupClause("product_id");
        }
        provider.init(dataSource);
        return provider;
    }

    private void explain(JdbcTemplate jdbc, String input, int chunk) throws Exception {
        var named = new NamedParameterJdbcTemplate(jdbc);
        var provider = pagingProvider(Strategy.GROUPED_PAGING);
        for (String sql : List.of(provider.generateFirstPageQuery(chunk), provider.generateRemainingPagesQuery(chunk))) {
            String plan = named.queryForObject("EXPLAIN ANALYZE " + sql,
                Map.of("snapshotId", input, "_product_id", 9_000), String.class);
            log("paging SQL=%s%n%s", sql, plan);
        }
        log("cursor SQL=%s%n%s", TOTALS, jdbc.queryForObject("EXPLAIN ANALYZE " + TOTALS, String.class, input));
    }

    private void verifyRestart(JobExecution failed, Strategy strategy, String output, long saved, long lastProduct) {
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(store.stageRows(output)).isEqualTo(saved);
        var step = failed.getStepExecutions().iterator().next();
        var checkpoint = jobExplorer.getStepExecution(failed.getId(), step.getId()).getExecutionContext();
        assertThat(checkpoint.getInt(READER + ".read.count")).isEqualTo((int) saved);
        if (strategy != Strategy.GROUPED_CURSOR) {
            assertThat(checkpoint.get(READER + ".start.after")).isEqualTo(Map.of("product_id", lastProduct));
        }
    }

    private static void verifyCompletion(JobExecution failed, JobExecution restarted, long remaining) {
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getId()).isNotEqualTo(failed.getId());
        assertThat(restarted.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(restarted.getStepExecutions().iterator().next().getReadCount()).isEqualTo(remaining);
    }

    private static String seed(JdbcTemplate jdbc, int products, int purchasesPerProduct) {
        String input = key();
        List<Object[]> rows = new ArrayList<>();
        long orderId = 1;
        for (int round = 0; round < purchasesPerProduct; round++) {
            for (long productId = 1; productId <= products; productId++) {
                rows.add(new Object[] {input, orderId++, productId, productId % 7 + 1});
                if (rows.size() == 5_000) {
                    insertInputs(jdbc, rows);
                    rows.clear();
                }
            }
        }
        if (!rows.isEmpty()) {
            insertInputs(jdbc, rows);
        }
        return input;
    }

    private static void insertInputs(JdbcTemplate jdbc, List<Object[]> rows) {
        jdbc.batchUpdate("""
            INSERT INTO ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
            VALUES (?, ?, ?, ?, '2026-08-17 01:00:00')
            """, rows);
    }

    private static String expectedChecksum(int products, int purchasesPerProduct) {
        RowChecksum checksum = new RowChecksum();
        for (int productId = 1; productId <= products; productId++) {
            checksum.addRow(productId, (productId % 7 + 1) * purchasesPerProduct);
        }
        return checksum.hex();
    }

    private String checksum(String output) {
        RowChecksum checksum = new RowChecksum();
        new JdbcTemplate(dataSource).query(
            "SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id", rs -> {
                checksum.addRow(rs.getLong("product_id"), rs.getLong("score"));
            }, output);
        return checksum.hex();
    }

    private List<String> ranking(String output) {
        return new JdbcTemplate(dataSource).query("""
            SELECT product_id, score, ROW_NUMBER() OVER (ORDER BY score DESC, product_id) AS ranking
            FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY ranking
            """, (rs, n) -> rs.getLong(1) + ":" + rs.getLong(2) + ":" + rs.getLong(3), output);
    }

    private static JobParameters parameters(String output) {
        return new JobParametersBuilder().addString("output", output).toJobParameters();
    }

    private static String key() {
        return "reader-exp-" + UUID.randomUUID();
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static void log(String format, Object... args) {
        System.out.println("[reader-strategy] " + String.format(format, args));
    }

    private static final class Measurements {
        private long openNanos;
        private long readNanos;
        private int readQueries;
        private int sumCalls;
        private long sumNanos;
        private int writeCalls;
        private long writeNanos;
    }

    private record TimedReader(ItemStreamReader<ReaderItem> delegate, Measurements measurements)
        implements ItemStreamReader<ReaderItem> {

        @Override
        public void open(ExecutionContext context) {
            long started = System.nanoTime();
            delegate.open(context);
            measurements.openNanos += System.nanoTime() - started;
            if (!(delegate instanceof JdbcPagingItemReader<?>)) {
                measurements.readQueries++;
            }
        }

        @Override
        public ReaderItem read() throws Exception {
            var paging = delegate instanceof JdbcPagingItemReader<?> reader ? reader : null;
            int previousPage = paging == null ? 0 : paging.getPage();
            long started = System.nanoTime();
            ReaderItem item = delegate.read();
            measurements.readNanos += System.nanoTime() - started;
            if (paging != null) {
                measurements.readQueries += paging.getPage() - previousPage;
            }
            return item;
        }

        @Override
        public void update(ExecutionContext context) {
            delegate.update(context);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
