package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingParametersValidationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 17);
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
        jdbc.update(
            "insert into purchase_activity "
                + "(created_at, updated_at, order_id, product_id, quantity, confirmed_at) "
                + "values (now(6), now(6), 42, 7, 2, ?)",
            LocalDateTime.parse("2026-08-17T00:00:00")
        );
    }

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions();
    }

    @DisplayName("SNP-008: snapshotId 가 기간과 revision 으로 만든 값과 다르면 launch 를 거절해 JobInstance 도 입력도 남기지 않는다.")
    @Test
    void rejectsSnapshotIdNotMatchingPeriodAndRevision() {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", PERIOD_START)
            .addLong("revision", 1L)
            .addString("snapshotId", "weekly-20260817-r2")
            .toJobParameters();

        assertRejectedWithoutTrace(params);
    }

    @DisplayName("RNK-001: periodStart 가 월요일이 아니면 launch 를 거절한다.")
    @Test
    void rejectsPeriodStartNotMonday() {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 18))
            .addLong("revision", 1L)
            .addString("snapshotId", "weekly-20260818-r1")
            .toJobParameters();

        assertRejectedWithoutTrace(params);
    }

    @DisplayName("JOB-002: 식별 parameter 가 빠지면 launch 를 거절한다.")
    @Test
    void rejectsMissingSnapshotId() {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", PERIOD_START)
            .addLong("revision", 1L)
            .toJobParameters();

        assertRejectedWithoutTrace(params);
    }

    @DisplayName("JOB-002: run.id 같은 다른 식별 parameter 가 붙으면 launch 를 거절한다.")
    @Test
    void rejectsUnknownIdentifyingParameter() {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", PERIOD_START)
            .addLong("revision", 1L)
            .addString("snapshotId", SNAPSHOT_ID)
            .addLong("run.id", 1L)
            .toJobParameters();

        assertRejectedWithoutTrace(params);
    }

    @DisplayName("JOB-002: revision 이 식별 parameter 가 아니면 launch 를 거절한다.")
    @Test
    void rejectsNonIdentifyingRevision() {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", PERIOD_START)
            .addLong("revision", 1L, false)
            .addString("snapshotId", SNAPSHOT_ID)
            .toJobParameters();

        assertRejectedWithoutTrace(params);
    }

    private void assertRejectedWithoutTrace(JobParameters params) {
        assertThatThrownBy(() -> jobLauncher.run(job, params))
            .isInstanceOf(JobParametersInvalidException.class);
        assertAll(
            () -> assertThat(jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, 0, 10)).isEmpty(),
            () -> assertThat(countRows("ranking_snapshot_input")).isZero(),
            () -> assertThat(countRows("ranking_snapshot")).isZero()
        );
    }

    private long countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
