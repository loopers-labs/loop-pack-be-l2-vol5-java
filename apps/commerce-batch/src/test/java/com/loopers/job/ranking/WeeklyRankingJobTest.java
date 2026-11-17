package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.job.ranking.WeeklyRankingTestConfig.HookedWeeklyRankingRepository;
import com.loopers.job.reconcile.ReconcileTestConfig.MutableClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 9주차 2단계: 주간 입력 고정(Step 1)과 상품별 점수 계산(Step 2). 실제 MySQL·JobRepository·step TM으로 Job을 직접 실행한다.
// @SpringBatchTest(step scope가 테스트 범위에 묶임)와 자동 rollback은 쓰지 않는다. Batch metadata와 snapshot 행은 지우지 않으므로
// snapshotId의 revision을 테스트마다 새로 정한다(같은 주·같은 revision이면 UNIQUE와 JobInstance가 부딪힌다).
// 원본 product_purchase_activity는 테스트마다 비우고 그 테스트의 행만 넣는다(streamer 엔티티가 만든 테이블).
@SpringBootTest
@Import(WeeklyRankingTestConfig.class)
@TestPropertySource(properties = {
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "spring.batch.job.enabled=false",
    "commerce.ranking.chunk-size=2"
})
class WeeklyRankingJobTest {
    private static final String PERIOD_START = "2026-08-17";
    private static final Instant FROZEN_AT = Instant.parse("2026-08-24T00:10:00Z");
    // 지시문 3절 checksum 규칙으로 계산한 과제 §6 4행의 값과 빈 입력의 값. 구현 결과를 옮긴 것이 아니다.
    private static final String FIXTURE_CHECKSUM = "d4026329d510b49a635a7f1bcad1e96f1f4dc879c47bd8b3fd2c77e3321da3ec";
    private static final String EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String READ_COUNT_KEY = WeeklyRankingJobConfig.READER + ".read.count";

    @Autowired private JobLauncher jobLauncher;
    @Autowired private Job weeklyRanking;
    @Autowired private JobExplorer jobExplorer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;
    @Autowired private HookedWeeklyRankingRepository repository;
    @Autowired private ItemWriter<ProductScore> weeklyScoreWriter;

    private int revision;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from product_purchase_activity");
        clock.set(FROZEN_AT);
        repository.disarm();
        revision = ThreadLocalRandom.current().nextInt(1, 1_000_000_000);
    }

    @AfterEach
    void clean() {
        repository.disarm();
        jdbc.update("delete from product_purchase_activity");
    }

    @Test
    @DisplayName("S1: 과제 입력 4행으로 실행하면 입력 4행이 복사되고 정보 행에 건수 4·checksum 상수가 남으며 점수는 상품별 합 {7:5, 8:1, 9:5}다")
    void freezesAndScores() throws Exception {
        insertFixture();

        JobExecution execution = run(PERIOD_START, snapshotId());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(inputs(snapshotId())).hasSize(4);
        assertThat(info(snapshotId()))
            .containsEntry("input_count", 4)
            .containsEntry("input_checksum", FIXTURE_CHECKSUM)
            .containsEntry("revision", revision)
            .containsEntry("frozen_at", LocalDateTime.ofInstant(FROZEN_AT, ZoneOffset.UTC));
        assertThat(stage(snapshotId())).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
    }

    @Test
    @DisplayName("S2: 구간 시작 정각의 구매는 들어가고 끝 정각과 다른 주의 구매는 빠진다")
    void periodBoundaries() throws Exception {
        source(50, 1, 1, "2026-08-17T00:00:00Z");
        source(51, 2, 1, "2026-08-23T23:59:59.999Z");
        source(52, 3, 1, "2026-08-24T00:00:00Z");
        source(53, 4, 1, "2026-08-16T23:59:59.999Z");

        JobExecution execution = run(PERIOD_START, snapshotId());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(inputs(snapshotId())).extracting(row -> row.get("product_id")).containsExactly(1L, 2L);
        assertThat(stage(snapshotId())).containsOnlyKeys(1L, 2L);
    }

    @Test
    @DisplayName("S3·S4: 상품 9 점수 계산 직전에 실패한 뒤 같은 주 늦은 구매(상품 6)가 들어와도, 같은 파라미터 재실행은 Step 1을 다시 돌지 않고 고정된 입력에서 이어 가 S1과 같은 점수가 된다")
    void restartContinuesFromFrozenInput() throws Exception {
        insertFixture();
        repository.failScoreOnce(9L);

        JobExecution first = run(PERIOD_START, snapshotId());

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stage(snapshotId())).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 5L, 8L, 1L));
        assertThat(step(first, WeeklyRankingJobConfig.FREEZE_STEP).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(first, WeeklyRankingJobConfig.CALCULATE_STEP).getExecutionContext().getInt(READ_COUNT_KEY)).isEqualTo(2);
        Map<String, Object> frozenInfo = info(snapshotId());
        List<Map<String, Object>> frozenInputs = inputs(snapshotId());

        source(45, 6, 4, "2026-08-20T01:00:00Z");
        JobExecution second = run(PERIOD_START, snapshotId());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        JobInstance instance = jobExplorer.getJobInstance(first.getJobInstance().getInstanceId());
        long freezeExecutions = jobExplorer.getJobExecutions(instance).stream()
            .flatMap(execution -> execution.getStepExecutions().stream())
            .filter(step -> step.getStepName().equals(WeeklyRankingJobConfig.FREEZE_STEP))
            .count();
        assertThat(freezeExecutions).isEqualTo(1);
        assertThat(info(snapshotId())).isEqualTo(frozenInfo);
        assertThat(inputs(snapshotId())).isEqualTo(frozenInputs).hasSize(4);
        assertThat(stage(snapshotId())).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 5L, 8L, 1L, 9L, 5L)).doesNotContainKey(6L);
    }

    @Test
    @DisplayName("S5: 이미 고정된 snapshotId를 다른 periodStart로 실행하면 실패하고 기존 정보·입력 행은 그대로다")
    void sameSnapshotIdDifferentPeriodFails() throws Exception {
        insertFixture();
        assertThat(run(PERIOD_START, snapshotId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> frozenInfo = info(snapshotId());
        List<Map<String, Object>> frozenInputs = inputs(snapshotId());

        Object result = runCatching("2026-08-24", snapshotId());

        assertThat(result).isNotEqualTo(BatchStatus.COMPLETED);
        assertThat(info(snapshotId())).isEqualTo(frozenInfo);
        assertThat(inputs(snapshotId())).isEqualTo(frozenInputs);
    }

    @Test
    @DisplayName("S6: 입력 행을 넣은 뒤 정보 행을 넣기 전에 실패하면 같은 TX라 입력 0행·정보 0행이다")
    void freezeIsAtomic() throws Exception {
        insertFixture();
        repository.failNextInfoInsert();

        JobExecution execution = run(PERIOD_START, snapshotId());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(inputs(snapshotId())).isEmpty();
        assertThat(infoCount(snapshotId())).isZero();
    }

    @Test
    @DisplayName("S7: Step 1이 원본을 읽은 뒤 TX가 열린 동안 다른 연결이 같은 주 원본 행을 INSERT하면 잠금 대기 1초 안에 성공한다")
    void freezeDoesNotLockSource() throws Exception {
        insertFixture();
        CountDownLatch inserted = new CountDownLatch(1);
        String[] otherResult = new String[1];
        repository.afterSourceRead(() -> {
            otherResult[0] = insertFromOtherConnection();
            inserted.countDown();
        });

        CompletableFuture<JobExecution> running = CompletableFuture.supplyAsync(() -> {
            try {
                return run(PERIOD_START, snapshotId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(inserted.await(20, TimeUnit.SECONDS)).as("다른 연결 INSERT 시도 끝남").isTrue();
        JobExecution execution = running.get(30, TimeUnit.SECONDS);
        assertThat(otherResult[0]).isEqualTo("성공");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("S8: 같은 상품 점수 chunk를 두 번 써도 점수는 절대값 5 그대로다(10이 아님)")
    void writerIsIdempotent() throws Exception {
        String id = snapshotId();
        Chunk<ProductScore> chunk = new Chunk<ProductScore>(List.of(new ProductScore(id, 7L, 5L)));

        weeklyScoreWriter.write(chunk);
        weeklyScoreWriter.write(chunk);

        assertThat(stage(id)).containsExactlyEntriesOf(Map.of(7L, 5L));
    }

    @Test
    @DisplayName("S9: 구매가 없는 주는 건수 0·빈 입력 checksum으로 고정되고 COMPLETED이며 점수 행이 없다")
    void emptyWeek() throws Exception {
        JobExecution execution = run(PERIOD_START, snapshotId());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(info(snapshotId())).containsEntry("input_count", 0).containsEntry("input_checksum", EMPTY_CHECKSUM);
        assertThat(stage(snapshotId())).isEmpty();
    }

    @Test
    @DisplayName("S10: 원본에 수량 0인 행이 있으면 건너뛰지 않고 FAILED이며 정보·입력 행이 없다")
    void contractViolationFails() throws Exception {
        insertFixture();
        source(46, 10, 0, "2026-08-20T01:00:00Z");

        JobExecution execution = run(PERIOD_START, snapshotId());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(infoCount(snapshotId())).isZero();
        assertThat(inputs(snapshotId())).isEmpty();
    }

    private String snapshotId() {
        return "weekly-20260817-r" + revision;
    }

    private void insertFixture() {
        source(42, 7, 2, "2026-08-17T01:00:00Z");
        source(42, 8, 1, "2026-08-17T01:00:00Z");
        source(43, 7, 3, "2026-08-18T01:00:00Z");
        source(44, 9, 5, "2026-08-19T01:00:00Z");
    }

    private void source(long orderId, long productId, int quantity, String confirmedAt) {
        jdbc.update("insert into product_purchase_activity(order_id, product_id, quantity, confirmed_at, created_at)"
            + " values (?, ?, ?, ?, ?)", orderId, productId, quantity, utc(confirmedAt), utc(confirmedAt));
    }

    private static LocalDateTime utc(String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }

    // 원본을 읽은 Step 1 TX가 열린 동안 별도 연결로 같은 주 원본 행을 넣는다. 잠금 대기 1초를 넘으면 실패로 기록한다.
    private String insertFromOtherConnection() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session innodb_lock_wait_timeout = 1");
            }
            try (PreparedStatement insert = connection.prepareStatement("insert into product_purchase_activity(order_id, product_id,"
                + " quantity, confirmed_at, created_at) values (?, ?, ?, ?, ?)")) {
                insert.setLong(1, 47);
                insert.setLong(2, 11);
                insert.setInt(3, 1);
                insert.setTimestamp(4, Timestamp.valueOf(utc("2026-08-21T01:00:00Z")));
                insert.setTimestamp(5, Timestamp.valueOf(utc("2026-08-21T01:00:00Z")));
                insert.executeUpdate();
            }
            return "성공";
        } catch (Exception e) {
            return "실패: " + e.getMessage();
        }
    }

    private JobExecution run(String periodStart, String snapshotId) throws Exception {
        return jobLauncher.run(weeklyRanking, parameters(periodStart, snapshotId));
    }

    // 파라미터 검증에서 시작 전에 거절되면 예외, 시작했다면 실행 상태를 돌려준다.
    private Object runCatching(String periodStart, String snapshotId) {
        Throwable[] failure = new Throwable[1];
        JobExecution[] execution = new JobExecution[1];
        failure[0] = catchThrowable(() -> execution[0] = run(periodStart, snapshotId));
        return execution[0] == null ? failure[0] : execution[0].getStatus();
    }

    private static JobParameters parameters(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart)
            .addString("snapshotId", snapshotId)
            .toJobParameters();
    }

    private StepExecution step(JobExecution execution, String name) {
        StepExecution inMemory = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(name)).findFirst().orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }

    private Map<String, Object> info(String snapshotId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select snapshot_id, period_start, period_end, revision, frozen_at,"
            + " input_count, input_checksum from ranking_snapshot where snapshot_id = ?", snapshotId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private long infoCount(String snapshotId) {
        return jdbc.queryForObject("select count(*) from ranking_snapshot where snapshot_id = ?", Long.class, snapshotId);
    }

    private List<Map<String, Object>> inputs(String snapshotId) {
        return jdbc.queryForList("select order_id, product_id, quantity, confirmed_at from ranking_snapshot_input"
            + " where snapshot_id = ? order by order_id, product_id", snapshotId);
    }

    private Map<Long, Long> stage(String snapshotId) {
        Map<Long, Long> scores = new LinkedHashMap<Long, Long>();
        jdbc.queryForList("select product_id, score from ranking_score_stage where snapshot_id = ? order by product_id", snapshotId)
            .forEach(row -> scores.put(((Number) row.get("product_id")).longValue(), ((Number) row.get("score")).longValue()));
        return scores;
    }
}
