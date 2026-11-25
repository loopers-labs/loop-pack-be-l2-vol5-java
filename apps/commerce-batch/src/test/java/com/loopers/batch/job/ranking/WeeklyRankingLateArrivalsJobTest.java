package com.loopers.batch.job.ranking;

import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.WEEK;
import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.snapshotId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=weeklyRankingLateArrivals"
})
@Import(WeeklyRankingJobFixture.class)
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingLateArrivalsJobTest {

    private static final String STEP = "countLateArrivals";

    @Autowired
    private WeeklyRankingJobFixture fixture;

    @Autowired
    @Qualifier("weeklyRankingLateArrivals")
    private Job job;

    @BeforeEach
    void setUp() {
        fixture.emptyTables();
        fixture.insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T01:00:00"));
        fixture.insertActivity(42L, 8L, 1, LocalDateTime.parse("2026-08-17T01:00:00"));
    }

    @AfterEach
    void tearDown() {
        fixture.removeExecutions();
    }

    @DisplayName("LAT-001: 공개본에 없는 그 주의 구매를 늦은 구매로 세고, 있으면 조치가 필요하므로 FAILED 로 끝낸다. 다음 주 구매는 세지 않는다.")
    @Test
    void failsWithCountWhenPurchasesArrivedAfterPublishedSnapshot() throws Exception {
        publishWeekAsFrozenNow(1);
        fixture.insertActivity(60L, 7L, 5, LocalDateTime.parse("2026-08-20T00:00:00"));
        fixture.insertActivity(70L, 9L, 1, LocalDateTime.parse("2026-08-24T00:00:00"));

        long executionId = fixture.launch(job, check("2026-08-25T09:00:00"));

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(fixture.stepNamed(executionId, STEP).getExecutionContext().getLong("lateArrivals"))
                .isEqualTo(1L)
        );
    }

    @DisplayName("LAT-002: 늦은 구매가 없으면 0 건으로 완료한다.")
    @Test
    void completesWithZeroWhenNothingArrivedLate() throws Exception {
        publishWeekAsFrozenNow(1);

        long executionId = fixture.launch(job, check("2026-08-25T09:00:00"));

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.stepNamed(executionId, STEP).getExecutionContext().getLong("lateArrivals"))
                .isZero()
        );
    }

    @DisplayName("LAT-003: 늦은 구매를 넣은 새 revision 이 공개되면, 처음 revision 이 아니라 공개본과 견주어 0 건이다.")
    @Test
    void comparesAgainstCurrentlyPublishedRevision() throws Exception {
        publishWeekAsFrozenNow(1);
        fixture.insertActivity(60L, 7L, 5, LocalDateTime.parse("2026-08-20T00:00:00"));
        publishWeekAsFrozenNow(2);

        long executionId = fixture.launch(job, check("2026-08-25T09:00:00"));

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.stepNamed(executionId, STEP).getExecutionContext().getLong("lateArrivals"))
                .isZero()
        );
    }

    @DisplayName("LAT-004: 그 주의 공개본이 없으면 견줄 기준이 없으므로 세지 않고 FAILED 로 끝낸다.")
    @Test
    void failsWhenWeekIsNotPublished() throws Exception {
        long executionId = fixture.launch(job, check("2026-08-25T09:00:00"));

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(fixture.stepNamed(executionId, STEP).getExecutionContext().containsKey("lateArrivals"))
                .isFalse()
        );
    }

    @DisplayName("LAT-005: 같은 주를 확인 시각만 바꿔 다시 확인하면 새 JobInstance 로 실행된다.")
    @Test
    void eachCheckIsANewInstance() throws Exception {
        publishWeekAsFrozenNow(1);

        long firstId = fixture.launch(job, check("2026-08-25T09:00:00"));
        long secondId = fixture.launch(job, check("2026-08-26T09:00:00"));

        assertAll(
            () -> assertThat(fixture.statusOf(firstId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.statusOf(secondId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.execution(secondId).getJobInstance().getInstanceId())
                .isNotEqualTo(fixture.execution(firstId).getJobInstance().getInstanceId())
        );
    }

    private JobParameters check(String checkedAt) {
        return new JobParametersBuilder()
            .addLocalDate("periodStart", WEEK)
            .addLocalDateTime("checkedAt", LocalDateTime.parse(checkedAt))
            .toJobParameters();
    }

    private void publishWeekAsFrozenNow(int revision) {
        String snapshotId = snapshotId(revision);
        fixture.jdbc().update("""
            insert into ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at)
            select ?, product_id, order_id, quantity, confirmed_at from purchase_activity
            where confirmed_at >= '2026-08-17' and confirmed_at < '2026-08-24'
            """, snapshotId);
        fixture.jdbc().update("""
            insert into ranking_snapshot
              (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum)
            values (?, ?, ?, 'FROZEN', now(6), 0, repeat('0', 64))
            """, snapshotId, WEEK, revision);
        fixture.jdbc().update("""
            insert into weekly_ranking_publication (period_start, snapshot_id, revision, published_at, frozen_at)
            values (?, ?, ?, now(6), now(6))
            on duplicate key update snapshot_id = values(snapshot_id), revision = values(revision)
            """, WEEK, snapshotId, revision);
    }
}
