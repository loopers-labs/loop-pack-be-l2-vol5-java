package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingLauncher;
import com.loopers.job.ranking.WeeklyRankingPublishMetadataFailureTest.FailingStepUpdate;
import com.loopers.job.ranking.WeeklyRankingTestConfig.HookedWeeklyRankingRepository;
import com.loopers.job.reconcile.ReconcileTestConfig.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 9주차 3단계 런처: 그 주에 실패·중단된 실행이 있으면 같은 parameter로 재시작하고, 없으면 최대 revision + 1을 만든다.
// w9d: 실패·중단된 실행이라도 pointer revision이 그 실행 revision 이상이면 끝난 것으로 보고 건너뛴다.
// 실제 MySQL·JobRepository로 런처 메서드를 직접 부른다. 테스트마다 무작위 주라 그 주의 이력은 이 테스트가 만든 것뿐이다.
// 공개 commit 뒤 기록 실패는 w9t 테스트의 JobRepository 대역(무장하지 않으면 그대로 위임)을 가져와 만든다.
@SpringBootTest
@Import({WeeklyRankingTestConfig.class, WeeklyRankingPublishMetadataFailureTest.FailingRepositoryConfig.class})
@TestPropertySource(properties = {
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "spring.batch.job.enabled=false",
    "commerce.ranking.chunk-size=2"
})
class WeeklyRankingLauncherTest {
    @Autowired private WeeklyRankingLauncher launcher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private HookedWeeklyRankingRepository repository;
    @Autowired private FailingStepUpdate failingStepUpdate;
    @Autowired private JobLauncher jobLauncher;
    @Autowired private JobExplorer jobExplorer;
    @Autowired private Job weeklyRanking;

    private RankingRows rows;
    private LocalDate period;

    @BeforeEach
    void prepare() {
        rows = new RankingRows(jdbc);
        rows.clearSource();
        clock.set(Instant.parse("2026-08-24T00:10:00Z"));
        repository.disarm();
        failingStepUpdate.disarm();
        period = RankingRows.randomMonday();
    }

    @AfterEach
    void clean() {
        failingStepUpdate.disarm();
        repository.disarm();
        rows.clearSource();
    }

    @Test
    @DisplayName("L1: 처음 실행하면 r1, COMPLETED 뒤에는 r2, FAILED 뒤에는 번호를 새로 만들지 않고 같은 snapshotId로 재시작해 COMPLETED가 된다")
    void launchesNextRevisionOrRestartsFailed() throws Exception {
        rows.insertFixture(period);

        JobExecution first = launcher.launch(period, null);
        JobExecution second = launcher.launch(period, null);
        repository.failScoreOnce(9L);
        JobExecution failed = launcher.launch(period, null);
        JobExecution restarted = launcher.launch(period, null);

        assertThat(first).isNotNull();
        assertThat(snapshotIdOf(first)).isEqualTo(RankingRows.snapshotId(period, 1));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotIdOf(second)).isEqualTo(RankingRows.snapshotId(period, 2));
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotIdOf(failed)).isEqualTo(RankingRows.snapshotId(period, 3));
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(snapshotIdOf(restarted)).isEqualTo(RankingRows.snapshotId(period, 3));
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(failed.getJobInstance().getInstanceId());
        assertThat(rows.revisions(period)).containsExactly(1, 2, 3);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 3));
    }

    @Test
    @DisplayName("L2: 같은 주 런처를 동시에 두 번 부르면 하나만 r1으로 COMPLETED이고 다른 하나는 이미 실행 중이라 거절되며 r2는 생기지 않는다")
    void concurrentLaunchRunsOnlyOne() throws Exception {
        rows.insertFixture(period);
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        // 테스트 대역에서만 첫 실행을 Step 1 안에 붙잡아 둔다(두 번째 호출이 끝나거나 20초가 지나면 놓는다).
        repository.afterSourceRead(() -> {
            firstRunning.countDown();
            awaitQuietly(secondDone);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        int success = 0;
        int rejected = 0;
        int technicalError = 0;
        try {
            Future<JobExecution> running = executor.submit(() -> launcher.launch(period, null));
            assertThat(firstRunning.await(20, TimeUnit.SECONDS)).as("첫 실행이 Step 1에 들어감").isTrue();
            repository.afterSourceRead(() -> { });

            try {
                launcher.launch(period, null);
                success++;
            } catch (JobExecutionAlreadyRunningException e) {
                rejected++;
            } catch (Exception e) {
                technicalError++;
            } finally {
                secondDone.countDown();
            }
            JobExecution first = running.get(30, TimeUnit.SECONDS);
            if (first != null && first.getStatus() == BatchStatus.COMPLETED) {
                success++;
            } else {
                technicalError++;
            }
        } finally {
            secondDone.countDown();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(List.of(success, rejected, technicalError)).containsExactly(1, 1, 0);
        assertThat(rows.revisions(period)).containsExactly(1);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 1));
    }

    @Test
    @DisplayName("D1: r2 공개 commit 뒤 Batch 기록 저장이 실패해 job UNKNOWN·pointer r2인 주에 런처를 부르면 r2를 재시작하지 않고 r3가 COMPLETED, pointer r3이며 r2 기록은 UNKNOWN 그대로다")
    void skipsUnknownExecutionAlreadyPublished() throws Exception {
        rows.insertFixture(period);
        assertThat(launcher.launch(period, null).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        failingStepUpdate.armFor(period);
        JobExecution unknown = launcher.launch(period, null);
        assertThat(failingStepUpdate.injected()).isTrue();
        assertThat(unknown.getStatus()).isEqualTo(BatchStatus.UNKNOWN);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 2));

        JobExecution next = launchWithoutRejection();

        assertThat(snapshotIdOf(next)).isEqualTo(RankingRows.snapshotId(period, 3));
        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.revisions(period)).containsExactly(1, 2, 3);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 3));
        assertThat(jobExplorer.getJobExecution(unknown.getId()).getStatus()).isEqualTo(BatchStatus.UNKNOWN);
        assertThat(jobExplorer.getJobExecutions(unknown.getJobInstance())).hasSize(1);
    }

    @Test
    @DisplayName("D2: r1 공개가 실패한 사이 r2가 공개돼 pointer r2·r1 FAILED인 주에 런처를 부르면 r1을 재시작하지 않고 r3가 COMPLETED, pointer r3이며 r1 기록은 FAILED 그대로다")
    void skipsFailedExecutionOlderThanPointer() throws Exception {
        rows.insertFixture(period);
        repository.failNextPublish();
        JobExecution failed = launcher.launch(period, null);
        assertThat(snapshotIdOf(failed)).isEqualTo(RankingRows.snapshotId(period, 1));
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run(RankingRows.snapshotId(period, 2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 2));

        JobExecution next = launchWithoutRejection();

        assertThat(snapshotIdOf(next)).isEqualTo(RankingRows.snapshotId(period, 3));
        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.revisions(period)).containsExactly(1, 2, 3);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 3));
        assertThat(jobExplorer.getJobExecution(failed.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jobExplorer.getJobExecutions(failed.getJobInstance())).hasSize(1);
    }

    @Test
    @DisplayName("D4: 건너뛸 r1 FAILED(pointer r2보다 작음)와 재시작할 r3 FAILED가 섞인 주에 런처를 부르면 r3를 같은 parameter로 재시작해 COMPLETED, pointer r3이며 r1 기록은 FAILED 그대로다")
    void restartsRemainingExecutionWhenOthersAreSkipped() throws Exception {
        rows.insertFixture(period);
        repository.failNextPublish();
        JobExecution skipped = launcher.launch(period, null);
        assertThat(skipped.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run(RankingRows.snapshotId(period, 2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        repository.failScoreOnce(9L);
        JobExecution failed = launcher.launch(period, null);
        assertThat(snapshotIdOf(failed)).isEqualTo(RankingRows.snapshotId(period, 3));
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 2));

        JobExecution restarted = launchWithoutRejection();

        assertThat(snapshotIdOf(restarted)).isEqualTo(RankingRows.snapshotId(period, 3));
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(failed.getJobInstance().getInstanceId());
        assertThat(rows.revisions(period)).containsExactly(1, 2, 3);
        assertThat(rows.pointer(period)).contains(RankingRows.snapshotId(period, 3));
        assertThat(jobExplorer.getJobExecution(skipped.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jobExplorer.getJobExecutions(skipped.getJobInstance())).hasSize(1);
    }

    private JobExecution launchWithoutRejection() {
        AtomicReference<JobExecution> launched = new AtomicReference<JobExecution>();
        assertThatCode(() -> launched.set(launcher.launch(period, null))).as("런처가 거절·실패 없이 실행함").doesNotThrowAnyException();
        return launched.get();
    }

    private JobExecution run(String snapshotId) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
            .addString("periodStart", period.toString())
            .addString("snapshotId", snapshotId)
            .toJobParameters();
        return jobLauncher.run(weeklyRanking, parameters);
    }

    private static String snapshotIdOf(JobExecution execution) {
        return execution.getJobParameters().getString("snapshotId");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
