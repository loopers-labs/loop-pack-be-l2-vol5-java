package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.RowChecksum;
import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.loopers.job.ranking.PurchaseActivityFixture.purchase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 입력 고정 Step의 대상 범위·revision 규칙·이미 있는 snapshot 처리와 stage 중복 저장 거부를 확인한다. */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=2"
})
class WeeklySnapshotFreezeTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private WeeklyRankingStore store;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private PurchaseActivityFixture activities;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        activities = new PurchaseActivityFixture(jdbc);
    }

    @DisplayName("기간 시작은 포함하고 끝은 제외하며, 같은 주문의 여러 품목을 모두 고정한다.")
    @Test
    void freezesPurchasesWithinWeek() throws Exception {
        activities.insert(
            purchase(200, 1, 1, "2026-09-06T23:59:59.999999"),  // 앞 주
            purchase(201, 1, 2, "2026-09-07T00:00:00"),         // 시작 시각: 포함
            purchase(201, 2, 3, "2026-09-07T00:00:00"),         // 같은 주문의 두 번째 품목
            purchase(202, 3, 4, "2026-09-13T23:59:59.999999"),  // 끝 직전: 포함
            purchase(203, 1, 5, "2026-09-14T00:00:00"));        // 끝 시각: 다음 주

        JobExecution execution = run("2026-09-07", "weekly-20260907-r1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(inputKeys("weekly-20260907-r1")).containsExactly("201-1", "201-2", "202-3");
        RowChecksum expected = new RowChecksum();
        expected.addRow(201, 1, 2, RowChecksum.utc(LocalDateTime.parse("2026-09-07T00:00:00")));
        expected.addRow(201, 2, 3, RowChecksum.utc(LocalDateTime.parse("2026-09-07T00:00:00")));
        expected.addRow(202, 3, 4, RowChecksum.utc(LocalDateTime.parse("2026-09-13T23:59:59.999999")));
        var manifest = store.findSnapshot("weekly-20260907-r1").orElseThrow();
        assertThat(manifest.status()).isEqualTo(WeeklyRankingStore.STATUS_COMPLETED);  // 같은 job의 공개 단계까지 끝남
        assertThat(manifest.revision()).isEqualTo(1);
        assertThat(manifest.inputCount()).isEqualTo(3);
        assertThat(manifest.productCount()).isEqualTo(3);
        assertThat(manifest.inputChecksum()).isEqualTo(expected.hex());
        assertThat(manifest.frozenAt()).isNotNull();
        assertThat(stageScores("weekly-20260907-r1"))
            .containsExactly(Map.entry(1L, 2L), Map.entry(2L, 3L), Map.entry(3L, 4L));
    }

    @DisplayName("revision은 그 기간의 마지막 + 1만 받는다. 새 revision은 늦은 구매를 포함하고 이전 revision의 입력은 그대로 둔다.")
    @Test
    void requiresNextRevisionAndKeepsEarlierInput() throws Exception {
        activities.insert(purchase(301, 5, 2, "2026-11-03T01:00:00"));

        JobExecution skipped = run("2026-11-02", "weekly-20261102-r2");
        assertThat(skipped.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(store.findSnapshot("weekly-20261102-r2")).isEmpty();
        assertThat(inputKeys("weekly-20261102-r2")).isEmpty();

        assertThat(run("2026-11-02", "weekly-20261102-r1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        activities.insert(purchase(302, 5, 4, "2026-11-04T01:00:00"));  // r1 고정 뒤 도착한 같은 주 구매

        assertThat(run("2026-11-02", "weekly-20261102-r2").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(inputKeys("weekly-20261102-r1")).containsExactly("301-5");
        assertThat(inputKeys("weekly-20261102-r2")).containsExactly("301-5", "302-5");
        assertThat(stageScores("weekly-20261102-r1")).containsExactly(Map.entry(5L, 2L));
        assertThat(stageScores("weekly-20261102-r2")).containsExactly(Map.entry(5L, 6L));
    }

    @DisplayName("이미 있는 snapshot은 다시 복사하지 않는다. 저장된 입력 요약이 같으면 그대로 쓰고, 다르면 충돌로 실패한다.")
    @Test
    void reusesOrRejectsExistingSnapshot() throws Exception {
        // 입력 고정 TX는 commit됐지만 Step 완료 기록이 실패한 상황: snapshot 정보와 입력이 이미 있다.
        activities.insert(
            purchase(401, 6, 3, "2026-09-22T01:00:00"),
            purchase(402, 6, 7, "2026-09-23T01:00:00"));  // 고정 뒤 도착한 구매
        jdbc.update("INSERT INTO ranking_snapshot_input VALUES ('weekly-20260921-r1', 401, 6, 3, '2026-09-22 01:00:00')");
        insertManifest("weekly-20260921-r1", "2026-09-21", store.summarizeInputs("weekly-20260921-r1"));

        assertThat(run("2026-09-21", "weekly-20260921-r1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(inputKeys("weekly-20260921-r1")).containsExactly("401-6");
        assertThat(stageScores("weekly-20260921-r1")).containsExactly(Map.entry(6L, 3L));

        // 저장된 snapshot 정보와 입력이 맞지 않는 경우
        insertManifest("weekly-20260928-r1", "2026-09-28", new WeeklyRankingStore.InputSummary(1, 1, "0".repeat(64)));
        activities.insert(purchase(403, 6, 1, "2026-09-29T01:00:00"));

        assertThat(run("2026-09-28", "weekly-20260928-r1").getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(inputKeys("weekly-20260928-r1")).isEmpty();
        assertThat(store.findSnapshot("weekly-20260928-r1").orElseThrow().inputChecksum()).isEqualTo("0".repeat(64));
    }

    @DisplayName("구매가 없는 주도 0건 snapshot으로 고정하고 점수 없이 끝난다.")
    @Test
    void freezesEmptyWeek() throws Exception {
        JobExecution execution = run("2026-10-05", "weekly-20261005-r1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var manifest = store.findSnapshot("weekly-20261005-r1").orElseThrow();
        assertThat(manifest.inputCount()).isZero();
        assertThat(manifest.productCount()).isZero();
        assertThat(manifest.inputChecksum()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(stageScores("weekly-20261005-r1")).isEmpty();
    }

    @DisplayName("수량이 0 이하인 구매 활동이 있으면 건너뛰지 않고 실패하고, 복사본과 snapshot 정보를 남기지 않는다.")
    @Test
    void rejectsNonPositiveQuantity() throws Exception {
        activities.insert(
            purchase(501, 7, 2, "2026-11-09T01:00:00"),
            purchase(502, 8, 0, "2026-11-10T01:00:00"));

        JobExecution execution = run("2026-11-09", "weekly-20261109-r1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions()).anySatisfy(e ->
            assertThat(e).hasMessageContaining("수량이 0 이하인 구매 활동이 1건"));
        assertThat(store.findSnapshot("weekly-20261109-r1")).isEmpty();
        assertThat(inputKeys("weekly-20261109-r1")).isEmpty();
        assertThat(stageScores("weekly-20261109-r1")).isEmpty();
    }

    @DisplayName("같은 snapshot에서 상품 점수를 중복 저장하면 실패하고 먼저 저장한 점수를 유지한다.")
    @Test
    void rejectsDuplicateScore() {
        String snapshotId = "weekly-20261012-r1";
        store.insertScores(snapshotId, List.of(new ProductScore(7, 5)));
        assertThatThrownBy(() -> store.insertScores(snapshotId, List.of(new ProductScore(7, 9))))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(stageScores(snapshotId)).containsExactly(Map.entry(7L, 5L));
    }

    @DisplayName("같은 snapshot에서 같은 순위를 두 번 저장하면 실패한다.")
    @Test
    void rejectsDuplicateRanking() {
        String snapshotId = "weekly-20261116-r1";
        jdbc.update("INSERT INTO ranking_entry (snapshot_id, product_id, score, ranking) VALUES (?, 7, 5, 1)", snapshotId);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO ranking_entry (snapshot_id, product_id, score, ranking) VALUES (?, 9, 5, 1)", snapshotId))
            .isInstanceOf(DuplicateKeyException.class);
    }

    private JobExecution run(String periodStart, String snapshotId) throws Exception {
        return jobLauncher.run(job, new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters());
    }

    private void insertManifest(String snapshotId, String periodStart, WeeklyRankingStore.InputSummary summary) {
        jdbc.update("""
            INSERT INTO ranking_snapshot
              (snapshot_id, period_start, revision, status, frozen_at, input_count, product_count, input_checksum)
            VALUES (?, ?, 1, 'FROZEN', UTC_TIMESTAMP(6), ?, ?, ?)
            """, snapshotId, periodStart, summary.inputCount(), summary.productCount(), summary.inputChecksum());
    }

    private List<String> inputKeys(String snapshotId) {
        return jdbc.query(
            "SELECT order_id, product_id FROM ranking_snapshot_input WHERE snapshot_id = ? ORDER BY order_id, product_id",
            (rs, n) -> rs.getLong("order_id") + "-" + rs.getLong("product_id"),
            snapshotId);
    }

    private List<Map.Entry<Long, Long>> stageScores(String snapshotId) {
        return jdbc.query(
            "SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
            (rs, n) -> Map.entry(rs.getLong("product_id"), rs.getLong("score")),
            snapshotId);
    }
}
