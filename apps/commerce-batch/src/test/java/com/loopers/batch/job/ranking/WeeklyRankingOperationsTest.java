package com.loopers.batch.job.ranking;

import com.loopers.batch.support.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.EXPECTED_RANKING;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.PERIOD_START;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.RESULT_CHECKSUM;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.calculatedAt;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.failOnceWritingRank;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertActivity;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertFixture;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.parameters;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.publishedRevision;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.ranking;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.revisionRow;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.scores;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * 실패 뒤 운영 절차. 정리 SQL과 닫기 SQL은 Job 설계 문서의 것과 같다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "weekly-ranking.chunk-size=2"
})
class WeeklyRankingOperationsTest {

    private static final Instant NOW = Instant.parse("2026-08-24T06:00:00Z");
    private static final String CLOSE_SQL = """
        UPDATE weekly_ranking_revisions
        SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = ?
        WHERE period_start = ? AND revision = ? AND status IN ('CALCULATING', 'COMPLETED')""";

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
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @MockitoSpyBean
    private WeeklyRankingRepository repository;

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

    @DisplayName("완성한 뒤 실행 기록이 실행 중으로 남으면 다시 실행이 거절되고, 기록을 정리해 다시 실행하면 세 Step이 다시 돌지만 아무것도 바뀌지 않는다.")
    @Test
    void finishesAfterCleaningStaleExecution() throws Exception {
        JobExecution first = run("1");
        markStarted(first);

        assertThatThrownBy(() -> run("1")).isInstanceOf(JobExecutionAlreadyRunningException.class);

        cleanUpStaleExecution(first.getId());
        clock.set(NOW.plusSeconds(3600));
        JobExecution second = run("1");

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStepExecutions()).hasSize(3)
            .allSatisfy(step -> assertThat(step.getWriteCount()).as(step.getStepName()).isZero());
        assertThat(step(second, WeeklyRankingJobConfig.RANK_STEP).getReadCount()).isZero();
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("result_checksum", RESULT_CHECKSUM)
            .containsEntry("job_execution_id", first.getId());
        assertThat(calculatedAt(jdbc, PERIOD_START, 1)).isEqualTo(LocalDateTime.parse("2026-08-24T06:00:00"));
    }

    @DisplayName("최근 회차를 닫으면 남은 완성 회차 중 가장 큰 회차가 공개본이 되고, 닫은 회차의 행은 남는다.")
    @Test
    void closingLatestRevisionFallsBackToPrevious() throws Exception {
        run("1");
        insertActivity(jdbc, 45, 9, 2, "2026-08-20T01:00:00");
        run("2");
        assertThat(publishedRevision(jdbc, PERIOD_START)).isEqualTo(2);

        assertThat(close(2, "검증 오류")).isEqualTo(1);

        assertThat(publishedRevision(jdbc, PERIOD_START)).isEqualTo(1);
        assertThat(revisionRow(jdbc, PERIOD_START, 2))
            .containsEntry("status", "CANCELLED")
            .containsEntry("cancel_reason", "검증 오류");
        assertThat(ranking(jdbc, PERIOD_START, 2)).hasSize(3);
    }

    @DisplayName("완성 회차를 모두 닫으면 공개본이 없다.")
    @Test
    void noPublishedRevisionWhenAllClosed() throws Exception {
        run("1");

        close(1, "검증 오류");

        assertThat(publishedRevision(jdbc, PERIOD_START)).isNull();
    }

    @DisplayName("닫은 완성 회차를 같은 파라미터로 다시 실행하면 완료된 작업이라 거절된다.")
    @Test
    void rejectsClosedCompletedRevision() throws Exception {
        run("1");
        run("2");
        close(2, "검증 오류");

        assertThatThrownBy(() -> run("2")).isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @DisplayName("완성한 뒤 실행 기록이 실행 중으로 남은 회차를 닫고 기록을 정리해 다시 실행하면, 점수 고정의 상태 확인에서 실패한다.")
    @Test
    void failsOnClosedRevisionAfterCleanup() throws Exception {
        run("1");
        JobExecution second = run("2");
        markStarted(second);
        close(2, "검증 오류");
        cleanUpStaleExecution(second.getId());

        JobExecution retried = run("2");

        assertThat(retried.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(retried.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("닫은 회차"));
        assertThat(revisionRow(jdbc, PERIOD_START, 2)).containsEntry("status", "CANCELLED");
        assertThat(publishedRevision(jdbc, PERIOD_START)).isEqualTo(1);
    }

    @DisplayName("닫은 회차도 번호에 포함하므로, 닫은 뒤에는 다음 번호로 새로 계산한다.")
    @Test
    void recalculatesWithNextNumberAfterClosing() throws Exception {
        run("1");
        run("2");
        close(2, "검증 오류");

        assertThat(run("3").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishedRevision(jdbc, PERIOD_START)).isEqualTo(3);
    }

    @DisplayName("끝내지 못한 계산 중 회차를 닫으면 점수 행은 남고, 다시 실행하면 순위 Step은 읽을 것이 없고 공개에서 실패한다. 다음 회차는 시작할 수 있다.")
    @Test
    void closesCalculatingRevision() throws Exception {
        failOnceWritingRank(repository, 3);
        assertThat(run("1").getStatus()).isEqualTo(BatchStatus.FAILED);

        assertThat(close(1, "순위 확인 실패")).isEqualTo(1);
        JobExecution retried = run("1");

        assertThat(retried.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(retried, WeeklyRankingJobConfig.RANK_STEP).getReadCount()).isZero();
        assertThat(retried.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("닫은 회차"));
        assertThat(scores(jdbc, PERIOD_START, 1)).hasSize(3);
        assertThat(revisionRow(jdbc, PERIOD_START, 1)).containsEntry("status", "CANCELLED");

        assertThat(run("2").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishedRevision(jdbc, PERIOD_START)).isEqualTo(2);
    }

    @DisplayName("묶음 사이에 회차가 닫히면 다음 묶음은 읽을 행이 없어 순위 Step이 끝나고 공개에서 실패한다. 다시 실행해도 아무것도 바뀌지 않는다.")
    @Test
    void stopsReadingWhenClosedBetweenChunks() throws Exception {
        closeAfterCommittingRank(2);

        JobExecution execution = run("1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution rank = step(execution, WeeklyRankingJobConfig.RANK_STEP);
        assertThat(rank.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rank.getReadCount()).isEqualTo(2);
        assertThat(rank.getWriteCount()).isEqualTo(2);
        assertThat(execution.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("닫은 회차"));
        List<List<Long>> closedScores = List.of(
            Arrays.asList(7L, 5L, 1L), Arrays.asList(8L, 1L, null), Arrays.asList(9L, 5L, 2L));
        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(closedScores);

        JobExecution retried = run("1");

        assertThat(retried.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(retried.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CANCELLED")
            .containsEntry("result_checksum", null);
        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(closedScores);
    }

    @DisplayName("묶음을 읽은 뒤 쓰기 직전에 회차가 닫히면 그 묶음의 쓰기가 실패하고, 닫힌 회차의 행은 더 바뀌지 않는다.")
    @Test
    void failsChunkWhenClosedBeforeWriting() throws Exception {
        closeBeforeWritingRank(3);

        JobExecution execution = run("1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(execution, WeeklyRankingJobConfig.RANK_STEP).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("계산 중인 회차"));
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CANCELLED")
            .containsEntry("result_checksum", null);
        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(List.of(
            Arrays.asList(7L, 5L, 1L), Arrays.asList(8L, 1L, null), Arrays.asList(9L, 5L, 2L)));
    }

    @DisplayName("이미 닫은 회차에 닫기 SQL을 다시 실행하면 바뀌는 행이 없다.")
    @Test
    void closeIsNotRepeated() throws Exception {
        run("1");

        assertThat(close(1, "검증 오류")).isEqualTo(1);
        assertThat(close(1, "다른 사유")).isZero();
        assertThat(revisionRow(jdbc, PERIOD_START, 1)).containsEntry("cancel_reason", "검증 오류");
    }

    private JobExecution run(String revision) throws Exception {
        return jobLauncher.run(job, parameters(PERIOD_START, revision));
    }

    // 완성된 회차에서 세 Step이 모두 다시 돌아도 아무것도 바뀌지 않는지 보려고 Job과 세 Step의 기록을 모두 되돌린다.
    // Step 기록이 완료로 남으면 다시 실행할 때 그 Step을 건너뛰어 확인이 돌지 않는다.
    private void markStarted(JobExecution execution) {
        jdbc.update("UPDATE BATCH_STEP_EXECUTION SET STATUS = 'STARTED', EXIT_CODE = 'EXECUTING', END_TIME = NULL "
            + "WHERE JOB_EXECUTION_ID = ?", execution.getId());
        jdbc.update("UPDATE BATCH_JOB_EXECUTION SET STATUS = 'STARTED', EXIT_CODE = 'UNKNOWN', END_TIME = NULL "
            + "WHERE JOB_EXECUTION_ID = ?", execution.getId());
    }

    private void cleanUpStaleExecution(long jobExecutionId) {
        LocalDateTime now = LocalDateTime.parse("2026-08-24T07:00:00");
        jdbc.update("""
            UPDATE BATCH_STEP_EXECUTION
            SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = ?, LAST_UPDATED = ?
            WHERE JOB_EXECUTION_ID = ? AND STATUS IN ('STARTED', 'UNKNOWN')""", now, now, jobExecutionId);
        jdbc.update("""
            UPDATE BATCH_JOB_EXECUTION
            SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = ?, LAST_UPDATED = ?
            WHERE JOB_EXECUTION_ID = ? AND STATUS IN ('STARTED', 'UNKNOWN')""", now, now, jobExecutionId);
    }

    private int close(int revision, String reason) {
        return jdbc.update(CLOSE_SQL, LocalDateTime.parse("2026-08-25T00:00:00"), reason, LocalDate.parse(PERIOD_START), revision);
    }

    // Step 트랜잭션에 묶이지 않도록 풀에서 새 연결을 받아 운영자처럼 닫는다.
    private void closeOnAnotherConnection(int revision) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLOSE_SQL)) {
            statement.setObject(1, LocalDateTime.parse("2026-08-25T00:00:00"));
            statement.setString(2, "순위 중 닫기");
            statement.setObject(3, LocalDate.parse(PERIOD_START));
            statement.setInt(4, revision);
            statement.executeUpdate();
        }
    }

    // 그 순위를 쓴 묶음이 커밋된 직후, 다음 묶음이 페이지를 읽기 전에 운영자가 회차를 닫는다.
    private void closeAfterCommittingRank(int rank) {
        doAnswer(invocation -> {
            Object written = invocation.callRealMethod();
            List<ProductRank> ranks = invocation.getArgument(2);
            if (ranks.stream().anyMatch(productRank -> productRank.rank() == rank)) {
                int revision = invocation.getArgument(1);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            closeOnAnotherConnection(revision);
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                });
            }
            return written;
        }).when(repository).writeRanks(any(), anyInt(), anyList());
    }

    // 앞 묶음은 커밋됐고, 그 순위를 쓰는 묶음을 쓰기 직전에 운영자가 회차를 닫는다.
    private void closeBeforeWritingRank(int rank) {
        doAnswer(invocation -> {
            List<ProductRank> ranks = invocation.getArgument(2);
            if (ranks.stream().anyMatch(productRank -> productRank.rank() == rank)) {
                closeOnAnotherConnection(invocation.getArgument(1));
            }
            return invocation.callRealMethod();
        }).when(repository).writeRanks(any(), anyInt(), anyList());
    }
}
