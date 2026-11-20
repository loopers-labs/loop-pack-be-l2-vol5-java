package com.loopers.batch.job.ranking;

import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.params;
import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.snapshotId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
@Import(WeeklyRankingJobFixture.class)
@Sql("/sql/purchase-activity.sql")
class FreezeSnapshotStepTest {

    private static final String R1 = snapshotId(1);
    private static final String R2 = snapshotId(2);
    private static final String HOLD_SNAPSHOT_ROW =
        "insert into ranking_snapshot "
            + "(snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum) "
            + "values (?, '2026-08-17', 1, 'FROZEN', now(6), 0, repeat('0', 64))";

    @Autowired
    private WeeklyRankingJobFixture fixture;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @BeforeEach
    void setUp() {
        fixture.emptyTables();
        fixture.insertActivity(40L, 7L, 9, LocalDateTime.parse("2026-08-16T23:59:59.999999"));
        fixture.insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T00:00:00"));
        fixture.insertActivity(42L, 8L, 1, LocalDateTime.parse("2026-08-17T00:00:00"));
        fixture.insertActivity(43L, 7L, 3, LocalDateTime.parse("2026-08-23T23:59:59.999999"));
        fixture.insertActivity(50L, 9L, 4, LocalDateTime.parse("2026-08-24T00:00:00"));
    }

    @AfterEach
    void tearDown() {
        fixture.removeExecutions();
    }

    @DisplayName("SNP-003, RNK-001, RNK-005: 기간 안의 구매 활동만 주문·상품 단위로 snapshot 입력에 복사한다.")
    @Test
    void copiesActivitiesInPeriod() throws Exception {
        long executionId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.frozenInputs(R1)).containsExactly("42:7:2", "42:8:1", "43:7:3")
        );
    }

    @DisplayName("SNP-001, SNP-007: snapshot 정보에 기간, revision, 상태, 입력 건수, 입력 checksum 을 남긴다.")
    @Test
    void recordsSnapshotInfo() throws Exception {
        fixture.launch(job, 1);

        Map<String, Object> snapshot = fixture.snapshotInfo(R1);
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

    @DisplayName("SNP-004: snapshot 정보를 쓰다 실패하면 이미 복사한 입력도 함께 rollback 되어 계산 대상이 남지 않는다.")
    @Test
    void rollsBackCopiedInputWhenSnapshotInfoFails() throws Exception {
        long executionId = fixture.launchWhileHolding(HOLD_SNAPSHOT_ROW, R1, job, params(1));

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(fixture.countRows("ranking_snapshot_input")).isZero(),
            () -> assertThat(fixture.countRows("ranking_snapshot")).isZero()
        );
    }

    @DisplayName("SNP-006, REV-001: 이미 고정한 snapshot 을 다시 고정하면 늦은 구매가 있어도 입력과 snapshot 정보를 그대로 두고 완료한다.")
    @Test
    void refreezingKeepsFrozenInput() throws Exception {
        fixture.forget(fixture.launch(job, 1));
        Map<String, Object> frozen = fixture.snapshotInfo(R1);
        fixture.insertActivity(60L, 7L, 5, LocalDateTime.parse("2026-08-20T00:00:00"));

        long executionId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.frozenInputs(R1)).containsExactly("42:7:2", "42:8:1", "43:7:3"),
            () -> assertThat(fixture.snapshotInfo(R1)).isEqualTo(frozen)
        );
    }

    @DisplayName("REV-002: 그 기간에 이미 있는 가장 큰 revision 보다 크지 않은 revision 은 고정하지 않고 실패한다.")
    @Test
    void rejectsRevisionNotAboveLatest() throws Exception {
        fixture.launch(job, 2);

        long executionId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(fixture.frozenInputs(R1)).isEmpty(),
            () -> assertThat(fixture.countRows("ranking_snapshot")).isEqualTo(1L)
        );
    }

    @DisplayName("REV-001, REV-002: 새 revision 은 늦은 구매까지 고정하고, 이전 revision 의 입력은 그대로 둔다.")
    @Test
    void newRevisionIncludesLatePurchase() throws Exception {
        fixture.launch(job, 1);
        fixture.insertActivity(60L, 7L, 5, LocalDateTime.parse("2026-08-20T00:00:00"));

        long executionId = fixture.launch(job, 2);

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.frozenInputs(R2)).containsExactly("42:7:2", "42:8:1", "43:7:3", "60:7:5"),
            () -> assertThat(fixture.frozenInputs(R1)).containsExactly("42:7:2", "42:8:1", "43:7:3")
        );
    }
}
