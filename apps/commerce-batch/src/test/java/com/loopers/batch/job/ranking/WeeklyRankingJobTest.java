package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.FreezeWeeklyScoresTasklet;
import com.loopers.batch.job.ranking.step.FrozenScoreReader;
import com.loopers.batch.support.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.EMPTY_CHECKSUM;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.EXPECTED_RANKING;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.PERIOD_START;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.RESULT_CHECKSUM;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.await;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.calculatedAt;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.count;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.failOnceWritingRank;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertActivity;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertFixture;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertRevision;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertScore;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.launchInBackground;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.parameters;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.publishedRevision;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.ranking;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.revisionRow;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.scores;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "weekly-ranking.chunk-size=2"
})
class WeeklyRankingJobTest {

    private static final Instant NOW = Instant.parse("2026-08-24T06:00:00Z");

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW, ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @MockitoSpyBean
    private WeeklyRankingRepository repository;

    @MockitoSpyBean
    private FreezeWeeklyScoresTasklet freezeTasklet;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private MutableClock clock;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        databaseCleanUp.truncateAllTables();
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions();
        clock.set(NOW);
        jdbc = new JdbcTemplate(dataSource);
        insertFixture(jdbc);
    }

    @DisplayName("한 번에 실행하면 세 Step을 거쳐 기대 순위와 기대 checksum으로 회차가 완성된다.")
    @Test
    void completesInOneRun() throws Exception {
        JobExecution execution = run("1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName, StepExecution::getWriteCount)
            .containsExactly(tuple(WeeklyRankingJobConfig.FREEZE_STEP, 3L), tuple(WeeklyRankingJobConfig.RANK_STEP, 3L),
                tuple(WeeklyRankingJobConfig.PUBLISH_STEP, 1L));
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "COMPLETED")
            .containsEntry("result_checksum", RESULT_CHECKSUM)
            .containsEntry("job_execution_id", execution.getId());
    }

    @DisplayName("순위를 매기다 실패하면 점수는 고정된 채 남고, 같은 파라미터로 다시 실행하면 점수 고정은 건너뛰고 순위를 처음부터 다시 매긴다. 그사이 들어온 구매는 섞이지 않는다.")
    @Test
    void rerankFromScratchAfterRankFailure() throws Exception {
        failOnceWritingRank(repository, 3);

        JobExecution first = run("1");

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(first.getStepExecutions()).extracting(StepExecution::getStepName, StepExecution::getStatus).containsExactly(
            tuple(WeeklyRankingJobConfig.FREEZE_STEP, BatchStatus.COMPLETED),
            tuple(WeeklyRankingJobConfig.RANK_STEP, BatchStatus.FAILED));
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CALCULATING")
            .containsEntry("result_checksum", null);
        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(List.of(
            Arrays.asList(7L, 5L, 1L), Arrays.asList(8L, 1L, null), Arrays.asList(9L, 5L, 2L)));
        assertThat(step(first, WeeklyRankingJobConfig.RANK_STEP).getExecutionContext().containsKey(FrozenScoreReader.NAME + ".read.count"))
            .isFalse();

        insertActivity(jdbc, 45, 9, 2, "2026-08-20T01:00:00");
        clock.set(NOW.plusSeconds(3600));
        JobExecution second = run("1");

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.RANK_STEP, WeeklyRankingJobConfig.PUBLISH_STEP);
        StepExecution rerank = step(second, WeeklyRankingJobConfig.RANK_STEP);
        assertThat(rerank.getReadCount()).isEqualTo(3);
        assertThat(rerank.getWriteCount()).isEqualTo(3);
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "COMPLETED")
            .containsEntry("result_checksum", RESULT_CHECKSUM)
            .containsEntry("job_execution_id", first.getId());
        assertThat(calculatedAt(jdbc, PERIOD_START, 1)).isEqualTo(LocalDateTime.parse("2026-08-24T06:00:00"));
    }

    @DisplayName("점수를 넣은 뒤 회차 정보를 넣기 직전에 실패하면 아무것도 남지 않고, 다시 실행하면 점수 고정부터 다시 한다.")
    @Test
    void refreezesAfterFreezeFailure() throws Exception {
        failOnceBeforeRevision();

        JobExecution first = run("1");

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count(jdbc, "weekly_ranking_scores")).isZero();
        assertThat(count(jdbc, "weekly_ranking_revisions")).isZero();

        JobExecution second = run("1");

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly(
            WeeklyRankingJobConfig.FREEZE_STEP, WeeklyRankingJobConfig.RANK_STEP, WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
        assertThat(revisionRow(jdbc, PERIOD_START, 1)).containsEntry("job_execution_id", second.getId());
    }

    // Repository를 거치면 예외 변환 프록시가 checked 예외를 RuntimeException으로 감싸므로, Tasklet에서 바로 던진다.
    @DisplayName("점수 고정 Tasklet에서 checked 예외가 나도 Step이 실패하고, 점수 고정 트랜잭션이 통째로 되돌아가 아무것도 남지 않는다.")
    @Test
    void rollsBackFreezeOnCheckedException() throws Exception {
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            Object status = invocation.callRealMethod();
            if (failed.compareAndSet(false, true)) {
                throw new IOException("점수를 넣은 뒤 checked 예외");
            }
            return status;
        }).when(freezeTasklet).execute(any(), any());

        JobExecution execution = run("1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count(jdbc, "weekly_ranking_scores")).isZero();
        assertThat(count(jdbc, "weekly_ranking_revisions")).isZero();
    }

    @DisplayName("구매가 없는 기간도 순위 0행으로 완성되고, checksum은 빈 문자열의 SHA-256이다.")
    @Test
    void completesEmptyPeriod() throws Exception {
        JobExecution execution = jobLauncher.run(job, parameters("2026-08-24", "1"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(execution, WeeklyRankingJobConfig.RANK_STEP).getReadCount()).isZero();
        assertThat(ranking(jdbc, "2026-08-24", 1)).isEmpty();
        assertThat(revisionRow(jdbc, "2026-08-24", 1)).containsEntry("result_checksum", EMPTY_CHECKSUM);
    }

    @DisplayName("계산 중인 회차에 기대와 다른 순위가 남아 있어도, 순위 Step이 비우고 처음부터 다시 매긴다.")
    @Test
    void replacesLeftoverRanks() throws Exception {
        insertRevision(jdbc, PERIOD_START, 1, "CALCULATING");
        insertScore(jdbc, PERIOD_START, 1, 7, 5, 2);
        insertScore(jdbc, PERIOD_START, 1, 8, 1, 1);
        insertScore(jdbc, PERIOD_START, 1, 9, 5, 3);

        JobExecution execution = run("1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
        assertThat(revisionRow(jdbc, PERIOD_START, 1)).containsEntry("result_checksum", RESULT_CHECKSUM);
    }

    @DisplayName("점수 고정 Step은 READ_COMMITTED라, 점수를 넣고 커밋하기 전에도 같은 기간의 새 구매가 잠금을 기다리지 않고 저장된다.")
    @Test
    void doesNotLockSource() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAfterScores(1, inserted, release);
        CompletableFuture<JobExecution> running = launchInBackground(jobLauncher, job, parameters(PERIOD_START, "1"));
        await(inserted);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
            try {
                statement.executeUpdate("""
                    INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
                    VALUES (45, 9, 10, '2026-08-20 01:00:00', NOW(6), NOW(6))""");
            } finally {
                statement.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
            }
        } finally {
            release.countDown();
        }

        assertThat(running.join().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
    }

    @DisplayName("새 회차는 그 기간의 마지막 회차 + 1이어야 한다. 어기면 실패하고 아무것도 남지 않는다.")
    @Test
    void revisionFollowsLast() throws Exception {
        JobExecution skipped = run("2");

        assertThat(skipped.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(skipped.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("마지막 회차"));
        assertThat(count(jdbc, "weekly_ranking_scores")).isZero();
        assertThat(count(jdbc, "weekly_ranking_revisions")).isZero();
    }

    @DisplayName("같은 기간에 계산 중인 회차가 있으면 다음 회차는 실패하고, 그 회차를 완성한 뒤에는 다음 회차가 실행된다.")
    @Test
    void nextRevisionWaitsForCalculatingRevision() throws Exception {
        failOnceWritingRank(repository, 3);
        assertThat(run("1").getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution blocked = run("2");

        assertThat(blocked.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(blocked.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("계산 중인 회차"));
        assertThat(scores(jdbc, PERIOD_START, 2)).isEmpty();

        assertThat(run("1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run("2").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForList("SELECT status FROM weekly_ranking_revisions ORDER BY revision", String.class))
            .containsExactly("COMPLETED", "COMPLETED");
    }

    @DisplayName("회차 2가 점수를 넣고 커밋하기 전이면 회차 3은 마지막 회차가 1로 보여 실패하고, 회차 2가 끝난 뒤에는 실행된다.")
    @Test
    void nextRevisionFailsWhileFreezing() throws Exception {
        assertThat(run("1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAfterScores(2, inserted, release);
        CompletableFuture<JobExecution> second = launchInBackground(jobLauncher, job, parameters(PERIOD_START, "2"));
        await(inserted);
        JobExecution blocked;
        try {
            blocked = run("3");
        } finally {
            release.countDown();
        }

        assertThat(blocked.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(blocked.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("마지막 회차"));
        assertThat(second.join().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run("3").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForList("SELECT revision FROM weekly_ranking_revisions ORDER BY revision", Integer.class))
            .isEqualTo(List.of(1, 2, 3));
    }

    @DisplayName("같은 회차가 실행 중이면 같은 파라미터의 실행은 거절된다.")
    @Test
    void rejectsSameRevisionWhileRunning() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAfterScores(1, inserted, release);
        CompletableFuture<JobExecution> running = launchInBackground(jobLauncher, job, parameters(PERIOD_START, "1"));
        await(inserted);
        try {
            assertThatThrownBy(() -> run("1")).isInstanceOf(JobExecutionAlreadyRunningException.class);
        } finally {
            release.countDown();
        }

        assertThat(running.join().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count(jdbc, "weekly_ranking_revisions")).isEqualTo(1);
    }

    @DisplayName("완성된 회차를 같은 파라미터로 다시 실행하면 거절되고, 늦은 구매는 회차를 올린 재계산에만 들어간다.")
    @Test
    void lateArrivalGoesToNextRevision() throws Exception {
        JobExecution first = run("1");
        insertActivity(jdbc, 45, 9, 2, "2026-08-20T01:00:00");

        assertThatThrownBy(() -> run("1")).isInstanceOf(JobInstanceAlreadyCompleteException.class);
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);

        assertThat(run("2").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(ranking(jdbc, PERIOD_START, 2))
            .isEqualTo(List.of(List.of(1L, 9L, 7L), List.of(2L, 7L, 5L), List.of(3L, 8L, 1L)));
        assertThat(publishedRevision(jdbc, PERIOD_START)).isEqualTo(2);
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
        assertThat(revisionRow(jdbc, PERIOD_START, 1)).containsEntry("job_execution_id", first.getId());
    }

    @DisplayName("파라미터가 모양에 맞지 않으면 Job이 시작되지 않고 JobInstance도 생기지 않는다.")
    @Test
    void rejectsInvalidParameters() {
        List<JobParameters> invalid = List.of(
            parameters(PERIOD_START, "01"),
            parameters(PERIOD_START, "0"),
            parameters("2026-08-19", "1"),
            parameters("2026-8-17", "1"),
            new JobParametersBuilder(parameters(PERIOD_START, "1")).addString("requestDate", "2026-08-24").toJobParameters(),
            new JobParametersBuilder().addString("periodStart", PERIOD_START).addString("revision", "1", false).toJobParameters());

        for (JobParameters parameters : invalid) {
            assertThatThrownBy(() -> jobLauncher.run(job, parameters)).isInstanceOf(JobParametersInvalidException.class);
        }
        assertThat(jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, 0, 10)).isEmpty();
    }

    @DisplayName("JVM 기본 시간대가 UTC나 America/Los_Angeles여도 같은 기간, 같은 결과다.")
    @Test
    void sameResultInOtherDefaultTimeZones() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "America/Los_Angeles")) {
                databaseCleanUp.truncateAllTables();
                new JobRepositoryTestUtils(jobRepository).removeJobExecutions();
                insertFixture(jdbc);
                TimeZone.setDefault(TimeZone.getTimeZone(zone));

                JobExecution execution = run("1");

                assertThat(execution.getStatus()).as(zone).isEqualTo(BatchStatus.COMPLETED);
                assertThat(ranking(jdbc, PERIOD_START, 1)).as(zone).isEqualTo(EXPECTED_RANKING);
                assertThat(calculatedAt(jdbc, PERIOD_START, 1)).as(zone).isEqualTo(LocalDateTime.parse("2026-08-24T06:00:00"));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private JobExecution run(String revision) throws Exception {
        return jobLauncher.run(job, parameters(PERIOD_START, revision));
    }

    private void failOnceBeforeRevision() {
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("회차 정보를 넣기 직전 실패");
            }
            return invocation.callRealMethod();
        }).when(repository).insertCalculatingRevision(any(), anyInt(), any(), anyLong());
    }

    // 그 회차의 점수를 넣은 뒤 커밋 전에 붙잡아 둔다.
    private void holdAfterScores(int revision, CountDownLatch inserted, CountDownLatch release) {
        doAnswer(invocation -> {
            Object rows = invocation.callRealMethod();
            inserted.countDown();
            await(release);
            return rows;
        }).when(repository).insertScores(any(), eq(revision));
    }
}
