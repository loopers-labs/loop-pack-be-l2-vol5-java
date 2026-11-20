package com.loopers.job.weeklyranking;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.domain.ranking.freeze.WeeklyRankingSnapshotInputRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.loopers.job.weeklyranking.PurchaseActivityRows.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/** §6 입력으로 같은 JobInstance 의 실패 → 재시작을 확인한다. 실패 스위치는 parameter 가 아니라 테스트의 spy 다. */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.weekly.chunk-size=2"
})
class RankingJobRestartTest {

    // §6 입력·결과를 설계 문서의 직렬화 규칙대로 손으로 만든 텍스트의 SHA-256 (shasum -a 256 으로 계산).
    private static final String INPUT_CHECKSUM = "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298";
    private static final String RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";

    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;
    @Autowired
    private JobExplorer jobExplorer;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean
    private WeeklyRankingSnapshotInputRepository inputRepository;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품 9 계산 직전에 실패하면 7·8 점수와 reader 위치 2 가 commit 되고, 같은 parameter 로 다시 실행하면 같은 instance 에서 9 만 계산해 완료한다.")
    @Test
    void resumesFromProductNine_inSameJobInstance() throws Exception {
        // arrange: §6 구매 활동 4행
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 42, 7, 2, "2026-08-17T01:00:00Z");
        insert(jdbc, 42, 8, 1, "2026-08-17T01:00:00Z");
        insert(jdbc, 43, 7, 3, "2026-08-18T01:00:00Z");
        insert(jdbc, 44, 9, 5, "2026-08-19T01:00:00Z");
        doThrow(new IllegalStateException("상품 9 계산 직전 장애")).doCallRealMethod()
            .when(inputRepository).sumQuantity("weekly-20260817-r1", 9L);
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.parse("2026-08-17"))
            .addLong("revision", 1L)
            .toJobParameters();

        // act: 첫 실행
        JobExecution first = jobLauncher.run(job, params);

        // assert: 실패 시점의 영속 checkpoint·stage·공개 상태
        StepExecution failedStep = jobExplorer.getStepExecution(
            first.getId(), stepNamed(first, WeeklyRankingJobConfig.CALCULATE_STEP).getId());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep.getExecutionContext().getInt("weeklyRankingProductReader.read.count")).isEqualTo(2);
        assertThat(stageScores(jdbc, "weekly-20260817-r1")).isEqualTo(Map.of(7L, 5L, 8L, 1L));
        assertThat(jdbc.queryForObject(
            "SELECT published_snapshot_id FROM weekly_ranking_publication WHERE period_start = '2026-08-17'", String.class))
            .isNull();

        // act: 같은 식별 parameter·같은 metadata 로 다시 실행
        JobExecution second = jobLauncher.run(job, params);

        // assert: 같은 instance 의 새 execution 이 입력 고정을 건너뛰고 상품 9 만 처리한다
        StepExecution resumedStep = stepNamed(second, WeeklyRankingJobConfig.CALCULATE_STEP);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .doesNotContain(WeeklyRankingJobConfig.FREEZE_STEP);
        assertThat(resumedStep.getReadCount()).isEqualTo(1);
        assertThat(resumedStep.getWriteCount()).isEqualTo(1);
        assertThat(stageScores(jdbc, "weekly-20260817-r1")).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM weekly_ranking_snapshot_input WHERE snapshot_id = 'weekly-20260817-r1'", Integer.class))
            .isEqualTo(4);

        // assert: 손으로 계산한 점수·순위와 checksum 으로 공개된다
        assertThat(rankingRows(jdbc, "weekly-20260817-r1")).containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(checksums(jdbc, "weekly-20260817-r1")).containsExactly(INPUT_CHECKSUM, RESULT_CHECKSUM);

        // act: 같은 입력을 실패 없이 계산하는 비교용 정상 실행(새 snapshot r2)
        JobExecution normal = jobLauncher.run(job, new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.parse("2026-08-17"))
            .addLong("revision", 2L)
            .toJobParameters());

        // assert: 재시작 결과와 정상 실행 결과가 같다
        assertThat(normal.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rankingRows(jdbc, "weekly-20260817-r2")).containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(checksums(jdbc, "weekly-20260817-r2")).containsExactly(INPUT_CHECKSUM, RESULT_CHECKSUM);
    }

    @DisplayName("첫 chunk 가 commit 되기 전에 실패하면 점수·위치가 남지 않고, 재시작은 처음 상품부터 한 번씩 계산한다.")
    @Test
    void recalculatesFirstChunk_whenFailedBeforeCommit() throws Exception {
        // arrange: §6 과 같은 모양을 다른 주에 둔다
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 52, 7, 2, "2026-08-24T01:00:00Z");
        insert(jdbc, 52, 8, 1, "2026-08-24T01:00:00Z");
        insert(jdbc, 53, 7, 3, "2026-08-25T01:00:00Z");
        insert(jdbc, 54, 9, 5, "2026-08-26T01:00:00Z");
        doThrow(new IllegalStateException("상품 8 계산 직전 장애")).doCallRealMethod()
            .when(inputRepository).sumQuantity("weekly-20260824-r1", 8L);
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.parse("2026-08-24"))
            .addLong("revision", 1L)
            .toJobParameters();

        // act
        JobExecution first = jobLauncher.run(job, params);

        // assert: 상품 7 의 계산은 rollback 되어 남지 않는다
        StepExecution failedStep = jobExplorer.getStepExecution(
            first.getId(), stepNamed(first, WeeklyRankingJobConfig.CALCULATE_STEP).getId());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep.getExecutionContext().getInt("weeklyRankingProductReader.read.count", 0)).isZero();
        assertThat(stageScores(jdbc, "weekly-20260824-r1")).isEmpty();

        // act
        JobExecution second = jobLauncher.run(job, params);

        // assert
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepNamed(second, WeeklyRankingJobConfig.CALCULATE_STEP).getReadCount()).isEqualTo(3);
        assertThat(stageScores(jdbc, "weekly-20260824-r1")).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
    }

    private static StepExecution stepNamed(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(stepName))
            .findFirst()
            .orElseThrow();
    }

    private static Map<Long, Long> stageScores(JdbcTemplate jdbc, String snapshotId) {
        Map<Long, Long> scores = new HashMap<>();
        jdbc.query("SELECT product_id, score FROM weekly_ranking_score_stage WHERE snapshot_id = ?",
            rs -> {
                scores.put(rs.getLong("product_id"), rs.getLong("score"));
            }, snapshotId);
        return scores;
    }

    private static List<String> rankingRows(JdbcTemplate jdbc, String snapshotId) {
        return jdbc.query("SELECT product_id, score, ranking FROM weekly_ranking WHERE snapshot_id = ? ORDER BY ranking",
            (rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("score") + ":" + rs.getInt("ranking"), snapshotId);
    }

    private static List<String> checksums(JdbcTemplate jdbc, String snapshotId) {
        return jdbc.queryForObject("SELECT input_checksum, result_checksum FROM weekly_ranking_snapshot WHERE snapshot_id = ?",
            (rs, n) -> List.of(rs.getString("input_checksum"), rs.getString("result_checksum")), snapshotId);
    }
}
