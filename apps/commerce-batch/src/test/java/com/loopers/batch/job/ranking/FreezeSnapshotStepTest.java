package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
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
class FreezeSnapshotStepTest {

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

    private final List<Long> executionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("truncate table purchase_activity");
        jdbc.execute("truncate table ranking_snapshot");
        jdbc.execute("truncate table ranking_snapshot_input");
        jdbc.execute("truncate table ranking_score_stage");

        insertActivity(40L, 7L, 9, LocalDateTime.parse("2026-08-16T23:59:59.999999"));
        insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T00:00:00"));
        insertActivity(42L, 8L, 1, LocalDateTime.parse("2026-08-17T00:00:00"));
        insertActivity(43L, 7L, 3, LocalDateTime.parse("2026-08-23T23:59:59.999999"));
        insertActivity(50L, 9L, 4, LocalDateTime.parse("2026-08-24T00:00:00"));
    }

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            executionIds.stream().map(jobExplorer::getJobExecution).toList()
        );
    }

    @DisplayName("SNP-003, RNK-001, RNK-005: 기간 안의 구매 활동만 주문·상품 단위로 snapshot 입력에 복사한다.")
    @Test
    void copiesActivitiesInPeriod() throws Exception {
        long executionId = launch(PERIOD_START, 1, SNAPSHOT_ID);

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(frozenInputs()).containsExactly("42:7:2", "42:8:1", "43:7:3")
        );
    }

    @DisplayName("SNP-001, SNP-007: snapshot 정보에 기간, revision, 상태, 입력 건수, 입력 checksum 을 남긴다.")
    @Test
    void recordsSnapshotInfo() throws Exception {
        launch(PERIOD_START, 1, SNAPSHOT_ID);

        Map<String, Object> snapshot = jdbc.queryForMap(
            "select period_start, revision, status, frozen_at, input_count, input_checksum "
                + "from ranking_snapshot where snapshot_id = ?",
            SNAPSHOT_ID
        );
        assertAll(
            () -> assertThat(snapshot.get("period_start").toString()).isEqualTo("2026-08-17"),
            () -> assertThat(snapshot.get("revision")).isEqualTo(1),
            () -> assertThat(snapshot.get("status")).isEqualTo("FROZEN"),
            () -> assertThat(snapshot.get("frozen_at")).isNotNull(),
            () -> assertThat(snapshot.get("input_count")).isEqualTo(3L),
            () -> assertThat(snapshot.get("input_checksum"))
                .isEqualTo("465da742466b69824eb7f8c3c06abddcaa05ce5c5e02ae33c904228adab589ae")
        );
    }

    @DisplayName("SNP-008: snapshotId 가 기간과 revision 으로 만든 값과 다르면 고정하지 않고 실패한다.")
    @Test
    void failsWhenSnapshotIdDoesNotMatchPeriodAndRevision() throws Exception {
        long executionId = launch(PERIOD_START, 1, "weekly-20260817-r2");

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(countRows("ranking_snapshot_input")).isZero(),
            () -> assertThat(countRows("ranking_snapshot")).isZero()
        );
    }

    @DisplayName("SNP-004: snapshot 정보를 쓰다 실패하면 이미 복사한 입력도 함께 rollback 되어 계산 대상이 남지 않는다.")
    @Test
    void rollsBackCopiedInputWhenSnapshotInfoFails() throws Exception {
        long executionId;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement(
                "insert into ranking_snapshot "
                    + "(snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum) "
                    + "values (?, '2026-08-17', 1, 'FROZEN', now(6), 0, repeat('0', 64))"
            )) {
                hold.setString(1, SNAPSHOT_ID);
                hold.executeUpdate();
            }
            executionId = launch(PERIOD_START, 1, SNAPSHOT_ID);
            blocker.rollback();
        }

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(countRows("ranking_snapshot_input")).isZero(),
            () -> assertThat(countRows("ranking_snapshot")).isZero()
        );
    }

    @DisplayName("SNP-006, REV-001: 이미 고정한 snapshot 을 다시 고정하면 늦은 구매가 있어도 입력과 snapshot 정보를 그대로 두고 완료한다.")
    @Test
    void refreezingKeepsFrozenInput() throws Exception {
        forget(launch(PERIOD_START, 1, SNAPSHOT_ID));
        Map<String, Object> frozen = snapshotInfo();
        insertActivity(60L, 7L, 5, LocalDateTime.parse("2026-08-20T00:00:00"));

        long executionId = launch(PERIOD_START, 1, SNAPSHOT_ID);

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(frozenInputs()).containsExactly("42:7:2", "42:8:1", "43:7:3"),
            () -> assertThat(snapshotInfo()).isEqualTo(frozen)
        );
    }

    @DisplayName("SNP-006: 같은 snapshotId 의 행이 다른 기간을 가리키면 덮지 않고 실패한다.")
    @Test
    void failsWhenExistingSnapshotHasDifferentPeriod() throws Exception {
        jdbc.update(
            "insert into ranking_snapshot "
                + "(snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum) "
                + "values (?, '2026-08-24', 1, 'FROZEN', now(6), 0, repeat('0', 64))",
            SNAPSHOT_ID
        );

        long executionId = launch(PERIOD_START, 1, SNAPSHOT_ID);

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(snapshotInfo().get("period_start").toString()).isEqualTo("2026-08-24"),
            () -> assertThat(countRows("ranking_snapshot_input")).isZero()
        );
    }

    private Map<String, Object> snapshotInfo() {
        return jdbc.queryForMap(
            "select period_start, revision, status, frozen_at, input_count, input_checksum "
                + "from ranking_snapshot where snapshot_id = ?",
            SNAPSHOT_ID
        );
    }

    private void forget(long executionId) {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            List.of(jobExplorer.getJobExecution(executionId))
        );
        executionIds.remove(executionId);
    }

    private long countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private long launch(LocalDate periodStart, long revision, String snapshotId) throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", periodStart)
            .addLong("revision", revision)
            .addString("snapshotId", snapshotId)
            .toJobParameters();
        JobExecution execution = jobLauncher.run(job, params);
        executionIds.add(execution.getId());
        return execution.getId();
    }

    private BatchStatus statusOf(long executionId) {
        return jobExplorer.getJobExecution(executionId).getStatus();
    }

    private List<String> frozenInputs() {
        return jdbc.query(
            "select order_id, product_id, quantity from ranking_snapshot_input "
                + "where snapshot_id = ? order by order_id, product_id",
            (rs, rowNum) -> rs.getLong("order_id") + ":" + rs.getLong("product_id") + ":" + rs.getInt("quantity"),
            SNAPSHOT_ID
        );
    }

    private void insertActivity(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        jdbc.update(
            "insert into purchase_activity "
                + "(created_at, updated_at, order_id, product_id, quantity, confirmed_at) "
                + "values (now(6), now(6), ?, ?, ?, ?)",
            orderId, productId, quantity, confirmedAtUtc
        );
    }
}
