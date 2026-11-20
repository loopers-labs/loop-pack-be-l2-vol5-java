package com.loopers.job.weeklyranking;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.domain.ranking.freeze.WeeklyRankingSnapshotInputRepository;
import com.loopers.domain.ranking.publish.WeeklyRankingRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.loopers.job.weeklyranking.PurchaseActivityRows.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * 고정 입력·revision·공개 흐름. JobInstance 가 시나리오끼리 섞이지 않도록 시나리오마다 다른 주를 쓰고,
 * 한 시나리오 안의 실행 사이에는 업무 DB·Batch metadata 를 지우지 않는다. 실패 스위치는 parameter 가 아니라 spy 다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.weekly.chunk-size=2"
})
class WeeklyRankingFlowTest {

    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean
    private WeeklyRankingSnapshotInputRepository inputRepository;
    @MockitoSpyBean
    private WeeklyRankingRepository rankingRepository;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("구간 시작은 포함하고 끝은 제외하며, 같은 주문의 여러 품목을 모두 고정한다.")
    @Test
    void freezesBoundaryAndAllItemsOfSameOrder() throws Exception {
        // arrange
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 40, 1, 1, "2026-09-06T23:59:59.999999Z");
        insert(jdbc, 41, 7, 2, "2026-09-07T00:00:00Z");
        insert(jdbc, 41, 8, 1, "2026-09-07T00:00:00Z");
        insert(jdbc, 45, 9, 1, "2026-09-13T23:59:59.999999Z");
        insert(jdbc, 46, 9, 1, "2026-09-14T00:00:00Z");

        // act
        JobExecution execution = jobLauncher.run(job, params("2026-09-07", 1));

        // assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForList("""
            SELECT order_id, product_id FROM weekly_ranking_snapshot_input
            WHERE snapshot_id = 'weekly-20260907-r1' ORDER BY order_id, product_id""")).containsExactly(
            Map.of("order_id", 41L, "product_id", 7L),
            Map.of("order_id", 41L, "product_id", 8L),
            Map.of("order_id", 45L, "product_id", 9L));
        assertThat(jdbc.queryForMap("""
            SELECT input_count, product_count FROM weekly_ranking_snapshot
            WHERE snapshot_id = 'weekly-20260907-r1'""")).isEqualTo(Map.of("input_count", 3, "product_count", 3));
    }

    @DisplayName("고정 뒤 도착한 늦은 구매는 재시작 결과에 섞이지 않고, 새 revision 에서만 반영되며 그 공개 전까지는 이전 revision 이 보인다.")
    @Test
    void lateArrivalOnlyEntersNewRevision() throws Exception {
        // arrange: r1·r2 모두 계산 중 한 번씩 실패시킨다
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 61, 7, 2, "2026-09-14T01:00:00Z");
        insert(jdbc, 62, 8, 1, "2026-09-15T01:00:00Z");
        doThrow(new IllegalStateException("r1 계산 장애")).doCallRealMethod()
            .when(inputRepository).sumQuantity("weekly-20260914-r1", 8L);
        doThrow(new IllegalStateException("r2 계산 장애")).doCallRealMethod()
            .when(inputRepository).sumQuantity("weekly-20260914-r2", 8L);

        // act: r1 실패 → 같은 주의 늦은 구매 도착 → r1 재시작
        JobExecution failedR1 = jobLauncher.run(job, params("2026-09-14", 1));
        insert(jdbc, 63, 7, 4, "2026-09-16T01:00:00Z");
        JobExecution resumedR1 = jobLauncher.run(job, params("2026-09-14", 1));

        // assert: 재시작은 고정한 2행만 계산한다
        assertThat(failedR1.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(resumedR1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rankingRows(jdbc, "weekly-20260914-r1")).containsExactly("7:2:1", "8:1:2");
        assertThat(publishedSnapshotId(jdbc, "2026-09-14")).isEqualTo("weekly-20260914-r1");

        // act: r2 는 늦은 구매를 포함해 고정하지만 계산 중 실패한다
        JobExecution failedR2 = jobLauncher.run(job, params("2026-09-14", 2));

        // assert: 새 결과 공개 전에는 r1 이 그대로 보인다
        assertThat(failedR2.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publishedSnapshotId(jdbc, "2026-09-14")).isEqualTo("weekly-20260914-r1");

        // act
        JobExecution resumedR2 = jobLauncher.run(job, params("2026-09-14", 2));

        // assert: 공개 뒤에는 늦은 구매를 포함한 r2 가 보인다
        assertThat(resumedR2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rankingRows(jdbc, "weekly-20260914-r2")).containsExactly("7:6:1", "8:1:2");
        assertThat(publishedSnapshotId(jdbc, "2026-09-14")).isEqualTo("weekly-20260914-r2");
    }

    @DisplayName("더 새 revision 이 이미 공개됐으면 오래된 실행은 pointer 를 되돌리지 않고 SUPERSEDED 로 완료한다.")
    @Test
    void staleRevisionDoesNotRollBackPointer() throws Exception {
        // arrange
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 71, 7, 1, "2026-09-21T01:00:00Z");
        doThrow(new IllegalStateException("r1 계산 장애")).doCallRealMethod()
            .when(inputRepository).sumQuantity("weekly-20260921-r1", 7L);
        JobExecution failedR1 = jobLauncher.run(job, params("2026-09-21", 1));
        JobExecution publishedR2 = jobLauncher.run(job, params("2026-09-21", 2));

        // act: 실패했던 r1 을 r2 공개 뒤에 재시작한다
        JobExecution resumedR1 = jobLauncher.run(job, params("2026-09-21", 1));

        // assert
        assertThat(failedR1.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publishedR2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resumedR1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resumedR1.getExitStatus().getExitCode()).isEqualTo("SUPERSEDED");
        assertThat(publishedSnapshotId(jdbc, "2026-09-21")).isEqualTo("weekly-20260921-r2");
        assertThat(snapshotStatus(jdbc, "weekly-20260921-r1")).isEqualTo("SUPERSEDED");
        assertThat(rankingRows(jdbc, "weekly-20260921-r1")).isEmpty();
    }

    @DisplayName("공개 TX 가 실패하면 새 ranking·pointer 가 남지 않고 이전 공개본이 유지되며, 재시작하면 공개된다.")
    @Test
    void keepsPreviousPublication_whenPublishTransactionFails() throws Exception {
        // arrange: r1 공개 뒤 새 구매가 들어온다
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 81, 7, 1, "2026-09-28T01:00:00Z");
        jobLauncher.run(job, params("2026-09-28", 1));
        insert(jdbc, 82, 8, 2, "2026-09-29T01:00:00Z");
        doThrow(new IllegalStateException("완성 ranking 저장 장애")).doCallRealMethod()
            .when(rankingRepository).saveAll(anyList());

        // act
        JobExecution failedR2 = jobLauncher.run(job, params("2026-09-28", 2));

        // assert
        assertThat(failedR2.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publishedSnapshotId(jdbc, "2026-09-28")).isEqualTo("weekly-20260928-r1");
        assertThat(snapshotStatus(jdbc, "weekly-20260928-r2")).isEqualTo("FROZEN");
        assertThat(rankingRows(jdbc, "weekly-20260928-r2")).isEmpty();

        // act
        JobExecution resumedR2 = jobLauncher.run(job, params("2026-09-28", 2));

        // assert
        assertThat(resumedR2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishedSnapshotId(jdbc, "2026-09-28")).isEqualTo("weekly-20260928-r2");
        assertThat(rankingRows(jdbc, "weekly-20260928-r2")).containsExactly("8:2:1", "7:1:2");
    }

    @DisplayName("구매가 없는 주도 빈 완성본으로 공개한다.")
    @Test
    void publishesEmptyWeek() throws Exception {
        // arrange
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // act
        JobExecution execution = jobLauncher.run(job, params("2026-10-05", 1));

        // assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishedSnapshotId(jdbc, "2026-10-05")).isEqualTo("weekly-20261005-r1");
        assertThat(snapshotStatus(jdbc, "weekly-20261005-r1")).isEqualTo("PUBLISHED");
        assertThat(rankingRows(jdbc, "weekly-20261005-r1")).isEmpty();
    }

    private static JobParameters params(String periodStart, long revision) {
        return new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.parse(periodStart))
            .addLong("revision", revision)
            .toJobParameters();
    }

    private static String publishedSnapshotId(JdbcTemplate jdbc, String periodStart) {
        return jdbc.queryForObject(
            "SELECT published_snapshot_id FROM weekly_ranking_period WHERE period_start = ?", String.class, periodStart);
    }

    private static String snapshotStatus(JdbcTemplate jdbc, String snapshotId) {
        return jdbc.queryForObject(
            "SELECT status FROM weekly_ranking_snapshot WHERE snapshot_id = ?", String.class, snapshotId);
    }

    private static List<String> rankingRows(JdbcTemplate jdbc, String snapshotId) {
        return jdbc.query("SELECT product_id, score, ranking FROM weekly_ranking WHERE snapshot_id = ? ORDER BY ranking",
            (rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("score") + ":" + rs.getInt("ranking"), snapshotId);
    }
}
