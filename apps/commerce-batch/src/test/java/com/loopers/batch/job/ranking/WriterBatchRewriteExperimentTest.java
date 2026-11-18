package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.chunk-size=5"
})
@Sql("/sql/purchase-activity.sql")
class WriterBatchRewriteExperimentTest {

    private static final int PRODUCTS = 5;
    private static final String SNAPSHOT_ID = "weekly-20260817-r1";

    @Nested
    @TestPropertySource(properties = "datasource.mysql-jpa.main.data-source-properties.rewriteBatchedStatements=true")
    class RewriteOn extends Measure {

        @DisplayName("EXP-032: rewriteBatchedStatements 를 켜면 chunk 의 upsert 가 여러 행 값의 한 문장으로 간다.")
        @Test
        void sendsOneStatementPerChunk() throws Exception {
            List<String> statements = writerStatementsDuringJob();

            assertAll(
                () -> assertThat(statements).hasSize(1),
                () -> assertThat(valueTuples(statements.get(0))).isEqualTo(PRODUCTS)
            );
        }
    }

    @Nested
    @TestPropertySource(properties = "datasource.mysql-jpa.main.data-source-properties.rewriteBatchedStatements=false")
    class RewriteOff extends Measure {

        @DisplayName("EXP-032: rewriteBatchedStatements 를 끄면 chunk 의 upsert 가 행마다 한 문장씩 간다.")
        @Test
        void sendsOneStatementPerRow() throws Exception {
            assertThat(writerStatementsDuringJob()).hasSize(PRODUCTS);
        }
    }

    abstract static class Measure {

        @Autowired
        private DataSource dataSource;

        @Autowired
        private JobLauncher jobLauncher;

        @Autowired
        private JobExplorer jobExplorer;

        @Autowired
        private JobRepository jobRepository;

        @Autowired
        @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
        private Job job;

        private JdbcTemplate jdbc;

        @BeforeEach
        void setUp() {
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("truncate table purchase_activity");
            jdbc.execute("truncate table ranking_snapshot");
            jdbc.execute("truncate table ranking_snapshot_input");
            jdbc.execute("truncate table ranking_score_stage");
            jdbc.execute("truncate table weekly_ranking");
            jdbc.execute("truncate table weekly_ranking_publication");
            for (long product = 1; product <= PRODUCTS; product++) {
                jdbc.update(
                    "insert into purchase_activity "
                        + "(created_at, updated_at, order_id, product_id, quantity, confirmed_at) "
                        + "values (now(6), now(6), ?, ?, 1, ?)",
                    product, product, LocalDateTime.parse("2026-08-17T01:00:00")
                );
            }
        }

        List<String> writerStatementsDuringJob() throws Exception {
            try (Connection root = rootConnection()) {
                root.createStatement().execute("set global log_output = 'TABLE'");
                root.createStatement().execute("truncate table mysql.general_log");
                root.createStatement().execute("set global general_log = 'ON'");

                JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
                    .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
                    .addLong("revision", 1L)
                    .addString("snapshotId", SNAPSHOT_ID)
                    .toJobParameters());

                root.createStatement().execute("set global general_log = 'OFF'");
                List<String> statements = new ArrayList<>();
                try (var rs = root.createStatement().executeQuery(
                    "select convert(argument using utf8mb4) from mysql.general_log "
                        + "where command_type in ('Query', 'Execute') "
                        + "and convert(argument using utf8mb4) like '%into ranking_score_stage%'"
                )) {
                    while (rs.next()) {
                        statements.add(rs.getString(1));
                    }
                }
                BatchStatus status = jobExplorer.getJobExecution(execution.getId()).getStatus();
                new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
                    List.of(jobExplorer.getJobExecution(execution.getId()))
                );
                statements.forEach(statement -> System.out.println("EXP-032 " + statement.replaceAll("\\s+", " ")));
                assertThat(status).isEqualTo(BatchStatus.COMPLETED);
                return statements;
            }
        }

        static int valueTuples(String statement) {
            return statement.split("'" + SNAPSHOT_ID + "'", -1).length - 1;
        }

        private static Connection rootConnection() throws SQLException {
            return DriverManager.getConnection(
                System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test"
            );
        }
    }
}
