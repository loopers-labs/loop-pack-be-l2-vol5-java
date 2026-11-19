package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ItemReadListener;
import org.springframework.batch.core.Job;
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
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.chunk-size=100"
})
@Sql("/sql/purchase-activity.sql")
class ReaderRestartExperimentTest {

    private static final int PRODUCTS = 10_000;
    private static final int CHUNK = 100;
    private static final long FAIL_AT = 9_001;
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
    private ItemStreamReader<Long> pagingReader;

    @Autowired
    @Qualifier("productScoreWriter")
    private ItemWriter<Long> writer;

    private JdbcTemplate jdbc;

    private final List<Long> executionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("purchase_activity", "ranking_snapshot", "ranking_snapshot_input",
            "ranking_score_stage", "weekly_ranking", "weekly_ranking_publication")) {
            jdbc.execute("truncate table " + table);
        }
        jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (var session = con.createStatement()) {
                session.execute("set session cte_max_recursion_depth = " + PRODUCTS);
            }
            try (var seed = con.prepareStatement("""
                insert into purchase_activity
                  (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
                with recursive seq (n) as (select 1 union all select n + 1 from seq where n < ?)
                select now(6), now(6), n, n, 1, timestamp('2026-08-17 01:00:00')
                from seq
                """)) {
                seed.setInt(1, PRODUCTS);
                return seed.executeUpdate();
            }
        });
    }

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            executionIds.stream().map(jobExplorer::getJobExecution).toList()
        );
    }

    @DisplayName("EXP-033: paging reader 는 chunk TX 의 커넥션만 쓰고 저장된 key 다음부터 다시 읽는다.")
    @Test
    void pagingReader() throws Exception {
        Result result = measure("exp033Paging", () -> pagingReader);

        assertAll(
            () -> assertThat(result.maxActiveConnections()).isEqualTo(1),
            () -> assertThat(result.rowsSentOnRestart()).isLessThanOrEqualTo(PRODUCTS - FAIL_AT + 1 + CHUNK)
        );
    }

    @DisplayName("EXP-033: cursor reader 는 커넥션을 하나 더 붙잡고, 재시작 때 처음부터 다시 읽어 이미 처리한 행을 버린다.")
    @Test
    void cursorReader() throws Exception {
        Result result = measure("exp033Cursor", () -> new JdbcCursorItemReaderBuilder<Long>()
            .name("snapshotProductCursor")
            .dataSource(dataSource)
            .sql("select distinct product_id from ranking_snapshot_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(statement -> statement.setString(1, SNAPSHOT_ID))
            .fetchSize(Integer.MIN_VALUE)
            .verifyCursorPosition(false)
            .saveState(true)
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build());

        assertAll(
            () -> assertThat(result.maxActiveConnections()).isEqualTo(2),
            () -> assertThat(result.rowsSentOnRestart()).isGreaterThanOrEqualTo(PRODUCTS)
        );
    }

    private Result measure(String jobName, ReaderFactory readers) throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
            .addLong("revision", 1L)
            .addString("snapshotId", SNAPSHOT_ID)
            .toJobParameters();

        long firstId;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement(
                "insert into ranking_score_stage (snapshot_id, product_id, score) values (?, ?, 0)"
            )) {
                hold.setString(1, SNAPSHOT_ID);
                hold.setLong(2, FAIL_AT);
                hold.executeUpdate();
            }
            firstId = launch(job(jobName, readers.create(), new AtomicInteger()), params);
            blocker.rollback();
        }
        long committedBeforeRestart = stagedRows();

        AtomicInteger maxActive = new AtomicInteger();
        resetStatementStats();
        long startedAt = System.nanoTime();
        long secondId = launch(job(jobName, readers.create(), maxActive), params);
        Duration restart = Duration.ofNanos(System.nanoTime() - startedAt);
        long rowsSent = readerRowsSent();

        Result result = new Result(
            jobExplorer.getJobExecution(firstId).getStatus(),
            committedBeforeRestart,
            jobExplorer.getJobExecution(secondId).getStatus(),
            maxActive.get(),
            rowsSent,
            stagedRows(),
            restart.toMillis()
        );
        System.out.println("EXP-033 " + jobName + " " + result);
        assertAll(
            () -> assertThat(result.firstStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(result.stagedBeforeRestart()).isEqualTo(FAIL_AT - 1),
            () -> assertThat(result.secondStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(result.stagedAfterRestart()).isEqualTo(PRODUCTS)
        );
        return result;
    }

    private Job job(String jobName, ItemStreamReader<Long> reader, AtomicInteger maxActive) throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        Step calculate = new StepBuilder(jobName + "Calculate", jobRepository)
            .<Long, Long>chunk(CHUNK, transactionManager)
            .reader(reader)
            .writer(writer)
            .listener(new ItemReadListener<Long>() {
                @Override
                public void afterRead(Long item) {
                    maxActive.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
                }
            })
            .build();
        return new JobBuilder(jobName, jobRepository).start(freezeSnapshotStep).next(calculate).build();
    }

    private long launch(Job job, JobParameters params) throws Exception {
        JobExecution execution = jobLauncher.run(job, params);
        executionIds.add(execution.getId());
        return execution.getId();
    }

    private long stagedRows() {
        return jdbc.queryForObject(
            "select count(*) from ranking_score_stage where snapshot_id = ?", Long.class, SNAPSHOT_ID
        );
    }

    private void resetStatementStats() throws SQLException {
        try (Connection root = rootConnection()) {
            root.createStatement().execute("truncate table performance_schema.events_statements_summary_by_digest");
        }
    }

    private long readerRowsSent() throws SQLException {
        long rowsSent = 0;
        try (Connection root = rootConnection();
             var rs = root.createStatement().executeQuery(
                 "select digest_text, count_star, sum_rows_sent "
                     + "from performance_schema.events_statements_summary_by_digest "
                     + "where lower(digest_text) like '%distinct%product_id%ranking_snapshot_input%'"
             )) {
            while (rs.next()) {
                System.out.println("EXP-033 digest calls=" + rs.getLong(2) + " rowsSent=" + rs.getLong(3)
                    + " " + rs.getString(1));
                rowsSent += rs.getLong(3);
            }
        }
        return rowsSent;
    }

    private static Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test");
    }

    @FunctionalInterface
    private interface ReaderFactory {
        ItemStreamReader<Long> create();
    }

    private record Result(
        BatchStatus firstStatus,
        long stagedBeforeRestart,
        BatchStatus secondStatus,
        int maxActiveConnections,
        long rowsSentOnRestart,
        long stagedAfterRestart,
        long restartMs
    ) {
    }
}
