package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.LateArrivalDetector;
import com.loopers.batch.job.ranking.LateArrivalDetector.Action;
import com.loopers.batch.job.ranking.LateArrivalDetector.Decision;
import com.loopers.batch.job.ranking.LateArrivalRevisionRunner;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.loopers.job.ranking.PurchaseActivityFixture.purchase;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 늦은 구매 감지(ADR 0079): 현재 UTC 주의 바로 앞 주를 보고 아무것도 안 함 / 재시작 / 다음 revision을 정한다.
 * 시각은 고정 Clock으로 주고, 테스트마다 다른 주를 쓴다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=2"
})
class LateArrivalDetectorTest {

    @Autowired
    private WeeklyRankingStore store;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private ApplicationEventPublisher events;

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

    @DisplayName("대상은 현재 UTC 주의 바로 앞 주이며, 그 주의 snapshot이 없으면 아무것도 하지 않는다.")
    @Test
    void doesNothingWithoutFirstRevision() {
        // 2027-05-12(수) 10:00 UTC → 대상 주 2027-05-03
        activities.insert(purchase(701, 1, 1, "2027-05-04T01:00:00"));

        Decision decision = detectorAt("2027-05-12T10:00:00").decide();

        assertThat(decision.periodStart()).isEqualTo(LocalDate.parse("2027-05-03"));
        assertThat(decision.action()).isEqualTo(Action.NONE);
    }

    @DisplayName("마지막 revision이 FROZEN이면 같은 snapshotId로 재시작하고, ABANDONED면 아무것도 하지 않는다.")
    @Test
    void restartsFrozenAndIgnoresAbandoned() {
        insertManifest("weekly-20270517-r1", "2027-05-17", "FROZEN");
        Decision frozen = detectorAt("2027-05-26T10:00:00").decide();
        assertThat(frozen.action()).isEqualTo(Action.RESTART);
        assertThat(frozen.snapshotId()).isEqualTo("weekly-20270517-r1");

        jdbc.update("UPDATE ranking_snapshot SET status = 'ABANDONED' WHERE snapshot_id = 'weekly-20270517-r1'");
        assertThat(detectorAt("2027-05-26T10:00:00").decide().action()).isEqualTo(Action.NONE);
    }

    @DisplayName("공개된 r1 뒤 원본이 늘지 않았으면 아무것도 하지 않고, 늘었어도 최근 30분 안에 저장됐으면 미루고, 조용해지면 r2를 고른다.")
    @Test
    void choosesNextRevisionOnlyAfterQuietPeriod() throws Exception {
        activities.insert(purchase(711, 1, 2, "2027-06-01T01:00:00"));
        assertThat(run("2027-05-31", "weekly-20270531-r1").getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(detectorAt("2027-06-09T10:00:00").decide().action()).isEqualTo(Action.NONE);

        // 09:50에 저장된 늦은 구매 → 10:00에는 아직 30분이 안 지남
        activities.insertStoredAt(LocalDateTime.parse("2027-06-09T09:50:00"), purchase(712, 2, 3, "2027-06-02T01:00:00"));
        Decision recent = detectorAt("2027-06-09T10:00:00").decide();
        assertThat(recent.action()).isEqualTo(Action.NONE);
        assertThat(recent.reason()).contains("최근 30분");

        Decision quiet = detectorAt("2027-06-09T10:30:00").decide();
        assertThat(quiet.action()).isEqualTo(Action.NEW_REVISION);
        assertThat(quiet.snapshotId()).isEqualTo("weekly-20270531-r2");
    }

    @DisplayName("실행기는 고른 revision으로 weeklyRanking을 실행해 새 revision을 공개하고, 그다음 확인에서는 할 일이 없다.")
    @Test
    void runnerPublishesNextRevision() throws Exception {
        activities.insert(purchase(721, 1, 2, "2027-06-15T01:00:00"));
        assertThat(run("2027-06-14", "weekly-20270614-r1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        activities.insertStoredAt(LocalDateTime.parse("2027-06-23T08:00:00"), purchase(722, 2, 3, "2027-06-16T01:00:00"));
        LateArrivalRevisionRunner runner = new LateArrivalRevisionRunner(
            detectorAt("2027-06-23T10:00:00"), jobLauncher, job, events);

        JobExecution execution = runner.runOnce().orElseThrow();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getJobParameters().getString("snapshotId")).isEqualTo("weekly-20270614-r2");
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM ranking_publication WHERE period_start = '2027-06-14'",
            String.class)).isEqualTo("weekly-20270614-r2");
        assertThat(runner.runOnce()).isEmpty();  // 원본 2건 = r2 입력 2건
    }

    private LateArrivalDetector detectorAt(String utcTime) {
        return new LateArrivalDetector(store, Clock.fixed(LocalDateTime.parse(utcTime).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private JobExecution run(String periodStart, String snapshotId) throws Exception {
        return jobLauncher.run(job, new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters());
    }

    private void insertManifest(String snapshotId, String periodStart, String status) {
        jdbc.update("""
            INSERT INTO ranking_snapshot
              (snapshot_id, period_start, revision, status, frozen_at, input_count, product_count, input_checksum)
            VALUES (?, ?, 1, ?, UTC_TIMESTAMP(6), 0, 0, ?)
            """, snapshotId, periodStart, status, "0".repeat(64));
    }
}
