package com.loopers.batch.job.ranking.freeze;

import com.loopers.batch.job.ranking.RankingTables;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 9주차 설계 2장(입력 고정). 실습 §6 fixture 를 실제 MySQL 원본 테이블에 넣고 job 을 실행한다 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    // 한 주문의 두 품목 사이를 포함해 모든 행이 페이지 경계가 되게 한다
    "weekly-ranking.freeze-page-size=1"
})
class WeeklyRankingFreezeTest {

    private static final LocalDate WEEK = LocalDate.parse("2026-08-17");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RankingTables tables;

    // batch metadata 는 지우지 않는다. 테스트마다 다른 기간 · revision 으로 JobInstance 를 나눈다
    @AfterEach
    void tearDown() {
        tables.truncate();
    }

    @DisplayName("주간 구매 활동을 고정하면, 같은 주문의 여러 품목까지 모든 행이 입력에 복사되고 manifest 에 건수 · checksum 이 남는다.")
    @Test
    void freezesAllItemsOfWeek() throws Exception {
        // arrange
        tables.confirmLabFixture(WEEK);
        WeeklyRankingParameters params = WeeklyRankingParameters.of(WEEK, 1);

        // act
        JobExecution execution = jobLauncher.run(job, params.toJobParameters());

        // assert
        String expectedChecksum = sha256("""
            42\t7\t2\t2026-08-17T01:00:00.000000Z
            42\t8\t1\t2026-08-17T01:00:00.000000Z
            43\t7\t3\t2026-08-18T01:00:00.000000Z
            44\t9\t5\t2026-08-19T01:00:00.000000Z
            """);
        assertAll(
            () -> assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(tables.inputsOf("weekly-20260817-r1"))
                .extracting(row -> row.get("order_id"), row -> row.get("product_id"), row -> row.get("quantity"))
                .containsExactly(tuple(42L, 7L, 2), tuple(42L, 8L, 1), tuple(43L, 7L, 3), tuple(44L, 9L, 5)),
            () -> assertThat(tables.manifestOf("weekly-20260817-r1"))
                .containsEntry("period_start", "2026-08-17")
                .containsEntry("revision", 1)
                .containsEntry("input_count", 4)
                .containsEntry("input_checksum", expectedChecksum)
        );
    }

    @DisplayName("주간 경계는 시작 포함 · 끝 제외이며, 저장된 UTC 시각을 시간대 변환 없이 비교한다.")
    @Test
    void appliesUtcWeekBoundary() throws Exception {
        // arrange
        tables.confirm(50L, "2026-08-23 23:59:59.999999", Map.of(1L, 1));
        tables.confirm(51L, "2026-08-24 00:00:00", Map.of(2L, 1));
        tables.confirm(52L, "2026-08-30 23:59:59.999999", Map.of(3L, 1));
        tables.confirm(53L, "2026-08-31 00:00:00", Map.of(4L, 1));
        WeeklyRankingParameters params = WeeklyRankingParameters.of(LocalDate.parse("2026-08-24"), 1);

        // act
        jobLauncher.run(job, params.toJobParameters());

        // assert
        assertThat(tables.inputsOf("weekly-20260824-r1")).extracting(row -> row.get("order_id")).containsExactly(51L, 52L);
    }

    @DisplayName("같은 snapshotId 의 manifest 가 이미 있으면, 입력을 다시 복사하지 않고 step 을 끝낸다.")
    @Test
    void doesNotCopyAgainWhenAlreadyFrozen() throws Exception {
        // arrange: 입력 commit 뒤 step 완료 기록 전에 멈춘 상태
        tables.confirm(60L, "2026-09-01 01:00:00", Map.of(7L, 1));
        jdbc.update("""
            insert into weekly_ranking_snapshot
                (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum, created_at, updated_at)
            values ('weekly-20260831-r1', '2026-08-31', 1, 'FROZEN', '2026-09-07 00:00:00', 0, 'earlier', now(6), now(6))""");

        // act
        JobExecution execution = jobLauncher.run(job, WeeklyRankingParameters.of(LocalDate.parse("2026-08-31"), 1).toJobParameters());

        // assert
        assertAll(
            () -> assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(tables.inputsOf("weekly-20260831-r1")).isEmpty(),
            () -> assertThat(tables.manifestOf("weekly-20260831-r1")).containsEntry("input_checksum", "earlier")
        );
    }

    @DisplayName("manifest 저장이 실패하면, 같은 TX 의 입력 복사도 rollback 되어 반쪽 snapshot 이 남지 않는다.")
    @Test
    void rollsBackInputsWhenManifestFails() throws Exception {
        // arrange: 같은 기간 · revision 의 다른 snapshotId 가 있어 manifest insert 가 유일 제약에 걸림
        tables.confirm(70L, "2026-09-08 01:00:00", Map.of(7L, 1, 8L, 2));
        jdbc.update("""
            insert into weekly_ranking_snapshot
                (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum, created_at, updated_at)
            values ('other', '2026-09-07', 1, 'FROZEN', '2026-09-14 00:00:00', 0, 'other', now(6), now(6))""");

        // act
        JobExecution execution = jobLauncher.run(job, WeeklyRankingParameters.of(LocalDate.parse("2026-09-07"), 1).toJobParameters());

        // assert
        assertAll(
            () -> assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(tables.inputsOf("weekly-20260907-r1")).isEmpty(),
            () -> assertThat(jdbc.queryForObject(
                "select count(*) from weekly_ranking_snapshot where snapshot_id = 'weekly-20260907-r1'", Integer.class)).isZero()
        );
    }

    @DisplayName("snapshotId 가 기간 · revision 과 맞지 않거나 기간 시작이 월요일이 아니면, 실행하지 않는다.")
    @Test
    void rejectsMismatchedParameters() {
        // arrange
        JobParameters mismatched = new JobParametersBuilder()
            .addLocalDate(WeeklyRankingParameters.PERIOD_START, WEEK, true)
            .addLong(WeeklyRankingParameters.REVISION, 2L, true)
            .addString(WeeklyRankingParameters.SNAPSHOT_ID, "weekly-20260817-r1", true)
            .toJobParameters();
        JobParameters tuesday = new JobParametersBuilder()
            .addLocalDate(WeeklyRankingParameters.PERIOD_START, LocalDate.parse("2026-08-18"), true)
            .addLong(WeeklyRankingParameters.REVISION, 1L, true)
            .addString(WeeklyRankingParameters.SNAPSHOT_ID, "weekly-20260818-r1", true)
            .toJobParameters();

        // act & assert
        assertAll(
            () -> assertThrows(JobParametersInvalidException.class, () -> jobLauncher.run(job, mismatched)),
            () -> assertThrows(JobParametersInvalidException.class, () -> jobLauncher.run(job, tuesday))
        );
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
