package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.chunk-size=100"
})
@Sql("/sql/purchase-activity.sql")
class GroupByReaderExperimentTest {

    private static final int PRODUCTS = 10_000;
    private static final int ORDERS_PER_PRODUCT = 5;
    private static final int CHUNK = 100;
    private static final int REPEATS = 3;
    private static final String SNAPSHOT_ID = "weekly-20260817-r1";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("freezeSnapshotStep")
    private Step freezeSnapshotStep;

    @Autowired
    @Qualifier("snapshotProductReader")
    private ItemStreamReader<Long> distinctProductReader;

    @Autowired
    @Qualifier("productScoreWriter")
    private ItemWriter<Long> groupBySumWriter;

    private final List<Long> executionIds = new ArrayList<>();

    private int runs;

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            executionIds.stream().map(jobExplorer::getJobExecution).toList()
        );
    }

    @DisplayName("EXP-034: groupClause 를 준 paging reader 는 page 마다 남은 상품 전부를 집계하고, chunk 의 상품만 group by 하는 writer 는 입력에 비례해 검사한다.")
    @Test
    void groupClauseReaggregatesRemainingProductsPerPage() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedAndFreeze(jdbc);
        long inputRows = jdbc.queryForObject(
            "select count(*) from ranking_snapshot_input where snapshot_id = ?", Long.class, SNAPSHOT_ID
        );

        MySqlPagingQueryProvider grouped = groupedQuery();
        String remainingPage = grouped.generateRemainingPagesQuery(CHUNK);
        System.out.println("EXP-034 sql " + remainingPage);
        String explain = jdbc.queryForList(
            "explain format=tree " + remainingPage.replace(":snapshotId", "'" + SNAPSHOT_ID + "'").replace("?", "5000"),
            String.class
        ).get(0);
        System.out.println("EXP-034 explain\n" + explain);

        Measurement current = null;
        Measurement groupBy = null;
        Map<Long, Long> currentScores = null;
        for (int attempt = 0; attempt <= REPEATS; attempt++) {
            Measurement c = measure(jdbc, "current", () -> new StepBuilder("exp034Current" + runs, jobRepository)
                .<Long, Long>chunk(CHUNK, transactionManager)
                .reader(distinctProductReader).writer(groupBySumWriter).build());
            currentScores = stagedScores(jdbc);
            Measurement g = measure(jdbc, "groupBy", () -> new StepBuilder("exp034GroupBy" + runs, jobRepository)
                .<Score, Score>chunk(CHUNK, transactionManager)
                .reader(groupByReader(grouped)).writer(valuesUpsert(jdbc)).build());
            assertThat(stagedScores(jdbc)).isEqualTo(currentScores);
            if (attempt > 0) {
                System.out.println("EXP-034 attempt=" + attempt + " " + c);
                System.out.println("EXP-034 attempt=" + attempt + " " + g);
                current = c;
                groupBy = g;
            }
        }
        System.out.println("EXP-034 inputRows=" + inputRows + " products=" + PRODUCTS);

        long remainingGroupsPerPage = (long) PRODUCTS * (PRODUCTS / CHUNK + 1) / 2;
        assertThat(current.rowsExamined()).isLessThanOrEqualTo(inputRows * 3);
        assertThat(current.statements()).isLessThanOrEqualTo(2L * (PRODUCTS / CHUNK) + 5);
        assertThat(groupBy.rowsExamined()).isGreaterThanOrEqualTo(remainingGroupsPerPage);
    }

    private Measurement measure(JdbcTemplate jdbc, String name, StepFactory steps) throws Exception {
        jdbc.execute("truncate table ranking_score_stage");
        resetStatementStats();
        runs++;
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
            .addLong("revision", 1L)
            .addString("snapshotId", SNAPSHOT_ID)
            .toJobParameters();
        long startedAt = System.nanoTime();
        JobExecution execution = jobLauncher.run(
            new JobBuilder("exp034" + name + runs, jobRepository).start(steps.create()).build(), params
        );
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        executionIds.add(execution.getId());
        assertThat(jobExplorer.getJobExecution(execution.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long[] stats = inputStatementStats();
        return new Measurement(name, stats[0], stats[1], stats[2], elapsedMs);
    }

    private void seedAndFreeze(JdbcTemplate jdbc) throws Exception {
        for (String table : List.of("purchase_activity", "ranking_snapshot", "ranking_snapshot_input",
            "ranking_score_stage", "weekly_ranking", "weekly_ranking_publication")) {
            jdbc.execute("truncate table " + table);
        }
        int rows = PRODUCTS * ORDERS_PER_PRODUCT;
        jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (var session = con.createStatement()) {
                session.execute("set session cte_max_recursion_depth = " + rows);
            }
            try (var seed = con.prepareStatement("""
                insert into purchase_activity
                  (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
                with recursive seq (n) as (select 1 union all select n + 1 from seq where n < ?)
                select now(6), now(6), n, (n - 1) % ? + 1, n % 3 + 1, timestamp('2026-08-17 01:00:00')
                from seq
                """)) {
                seed.setInt(1, rows);
                seed.setInt(2, PRODUCTS);
                return seed.executeUpdate();
            }
        });
        JobExecution freeze = jobLauncher.run(
            new JobBuilder("exp034Freeze", jobRepository).start(freezeSnapshotStep).build(),
            new JobParametersBuilder()
                .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
                .addLong("revision", 1L)
                .addString("snapshotId", SNAPSHOT_ID)
                .toJobParameters()
        );
        executionIds.add(freeze.getId());
    }

    private MySqlPagingQueryProvider groupedQuery() {
        MySqlPagingQueryProvider query = new MySqlPagingQueryProvider();
        query.setSelectClause("product_id, sum(quantity) as score");
        query.setFromClause("ranking_snapshot_input");
        query.setWhereClause("snapshot_id = :snapshotId");
        query.setGroupClause("product_id");
        query.setSortKeys(Map.of("product_id", Order.ASCENDING));
        return query;
    }

    private JdbcPagingItemReader<Score> groupByReader(MySqlPagingQueryProvider query) throws Exception {
        JdbcPagingItemReader<Score> reader = new JdbcPagingItemReaderBuilder<Score>()
            .name("snapshotScoreReader")
            .dataSource(dataSource)
            .queryProvider(query)
            .parameterValues(Map.of("snapshotId", SNAPSHOT_ID))
            .pageSize(CHUNK)
            .rowMapper((rs, rowNum) -> new Score(rs.getLong("product_id"), rs.getLong("score")))
            .build();
        reader.afterPropertiesSet();
        return reader;
    }

    private ItemWriter<Score> valuesUpsert(JdbcTemplate jdbc) {
        return chunk -> jdbc.batchUpdate("""
            insert into ranking_score_stage (snapshot_id, product_id, score) values (?, ?, ?) as new
            on duplicate key update score = new.score
            """,
            chunk.getItems(), chunk.size(),
            (statement, item) -> {
                statement.setString(1, SNAPSHOT_ID);
                statement.setLong(2, item.productId());
                statement.setLong(3, item.score());
            }
        );
    }

    private Map<Long, Long> stagedScores(JdbcTemplate jdbc) {
        Map<Long, Long> scores = new TreeMap<>();
        jdbc.query(
            "select product_id, score from ranking_score_stage where snapshot_id = ?",
            rs -> {
                scores.put(rs.getLong("product_id"), rs.getLong("score"));
            },
            SNAPSHOT_ID
        );
        return scores;
    }

    private void resetStatementStats() throws SQLException {
        try (Connection root = rootConnection()) {
            root.createStatement().execute("truncate table performance_schema.events_statements_summary_by_digest");
        }
    }

    private long[] inputStatementStats() throws SQLException {
        try (Connection root = rootConnection();
             var rs = root.createStatement().executeQuery(
                 "select coalesce(sum(count_star), 0), coalesce(sum(sum_rows_examined), 0), "
                     + "coalesce(sum(sum_rows_sent), 0) "
                     + "from performance_schema.events_statements_summary_by_digest "
                     + "where lower(digest_text) like '%from `ranking_snapshot_input`%'"
             )) {
            rs.next();
            return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
        }
    }

    private static Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test");
    }

    @FunctionalInterface
    private interface StepFactory {
        Step create() throws Exception;
    }

    private record Score(long productId, long score) {
    }

    private record Measurement(String name, long statements, long rowsExamined, long rowsSent, long elapsedMs) {
    }
}
