package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.PublishRankingTasklet;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.job.ranking.WeeklyRankingTestConfig.HookedWeeklyRankingRepository;
import com.loopers.job.reconcile.ReconcileTestConfig.MutableClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// 9주차 3단계: 공개 Step(Step 3)과 K 확장 job. 실제 MySQL·JobRepository·step TM으로 Job을 직접 실행한다.
// @SpringBatchTest와 자동 rollback은 쓰지 않는다. 테스트마다 무작위 주를 써서 pointer·metadata가 다른 테스트와 섞이지 않게 한다.
@SpringBootTest
@Import(WeeklyRankingTestConfig.class)
@TestPropertySource(properties = {
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "spring.batch.job.enabled=false",
    "commerce.ranking.chunk-size=2"
})
class WeeklyRankingPublishTest {
    // 지시문 A-2 결과 checksum 규칙으로 참모가 계산한 과제 §6의 값("7\t5\t1\n9\t5\t2\n8\t1\t3\n"). 구현 결과를 옮긴 것이 아니다.
    private static final String RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";
    private static final List<String> FULL_RANKING = List.of("1/7/5", "2/9/5", "3/8/1");

    @Autowired private JobLauncher jobLauncher;
    @Autowired private Job weeklyRanking;
    @Autowired private Job weeklyRankingExtend;
    @Autowired private JobExplorer jobExplorer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;
    @Autowired private HookedWeeklyRankingRepository repository;

    private RankingRows rows;
    private LocalDate period;

    @BeforeEach
    void prepare() {
        rows = new RankingRows(jdbc);
        rows.clearSource();
        clock.set(Instant.parse("2026-08-24T00:10:00Z"));
        repository.disarm();
        period = RankingRows.randomMonday();
    }

    @AfterEach
    void clean() {
        repository.disarm();
        rows.clearSource();
    }

    @Test
    @DisplayName("P1: 고정→계산→공개를 한 번 실행하면 완성 랭킹이 7(1위)·9(2위)·8(3위), 점수 5·5·1이고 pointer가 그 snapshot을 가리키며 결과 checksum이 상수와 같다")
    void publishesRanking() throws Exception {
        rows.insertFixture(period);

        JobExecution execution = run(id(1), null);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.results(id(1))).containsExactlyElementsOf(FULL_RANKING);
        assertThat(rows.pointer(period)).contains(id(1));
        assertThat(publishStep(execution).getExecutionContext().getString(PublishRankingTasklet.RESULT_CHECKSUM_KEY))
            .isEqualTo(RESULT_CHECKSUM);
    }

    @Test
    @DisplayName("P2: 점수 stage가 덜 찬 snapshot(상품 9 없음)을 공개하면 실패하고 그 snapshot 랭킹 0행, pointer는 이전 공개본 그대로다")
    void incompleteStageIsNotPublished() throws Exception {
        rows.insertFixture(period);
        assertThat(run(id(1), null).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        repository.failScoreOnce(9L);
        assertThat(run(id(2), null).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(rows.stage(id(2))).hasSize(2);

        JobExecution execution = extend(id(2), 150);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(rows.results(id(2))).isEmpty();
        assertThat(rows.pointer(period)).contains(id(1));
    }

    @Test
    @DisplayName("P3: 완성 랭킹 INSERT 뒤 pointer 공개 전에 실패하면 같은 TX라 새 랭킹 0행이고 pointer는 이전 공개본 그대로다")
    void publishIsAtomic() throws Exception {
        rows.insertFixture(period);
        assertThat(run(id(1), null).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        repository.failNextPublish();

        JobExecution execution = run(id(2), null);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(rows.results(id(2))).isEmpty();
        assertThat(rows.pointer(period)).contains(id(1));
    }

    @Test
    @DisplayName("P4: 이미 공개된 snapshot을 같은 K로 다시 공개하면 COMPLETED이고 완성 랭킹·pointer가 바뀌지 않는다")
    void republishingSameSnapshotChangesNothing() throws Exception {
        rows.insertFixture(period);
        assertThat(run(id(1), null).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JobExecution execution = extend(id(1), 150);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishStep(execution).getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(rows.results(id(1))).containsExactlyElementsOf(FULL_RANKING);
        assertThat(rows.pointer(period)).contains(id(1));
    }

    @Test
    @DisplayName("P5: r1 공개가 실패한 사이 r2가 공개된 뒤 r1을 재시작하면 COMPLETED·종료 코드 NEWER_PUBLISHED이고 pointer는 r2 그대로다")
    void olderRevisionDoesNotOverwriteNewer() throws Exception {
        rows.insertFixture(period);
        repository.failNextPublish();
        assertThat(run(id(1), null).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run(id(2), null).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.pointer(period)).contains(id(2));

        JobExecution restarted = run(id(1), null);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishStep(restarted).getExitStatus().getExitCode()).isEqualTo(PublishRankingTasklet.NEWER_PUBLISHED);
        assertThat(rows.pointer(period)).contains(id(2));
        assertThat(rows.results(id(1))).containsExactlyElementsOf(FULL_RANKING);
    }

    @Test
    @DisplayName("P5-1: r1 공개 TX가 시작된 뒤 다른 연결이 r2를 pointer에 공개하고 commit하면, r1은 pointer를 덮지 않고 종료 코드 NEWER_PUBLISHED로 끝난다")
    void newerPublishedDuringPublishIsDetected() throws Exception {
        rows.insertFixture(period);
        repository.failNextPublish();
        assertThat(run(id(2), null).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(rows.pointer(period)).isEmpty();
        repository.beforePublish(() -> publishFromOtherConnection(id(2)));

        JobExecution execution = run(id(1), null);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.pointer(period)).contains(id(2));
        assertThat(publishStep(execution).getExitStatus().getExitCode()).isEqualTo(PublishRankingTasklet.NEWER_PUBLISHED);
    }

    @Test
    @DisplayName("P6: K=2로 공개한 뒤 K=3으로 확장하면 3위만 추가되고 1·2위 행과 pointer는 그대로다")
    void extendAddsOnlyNextRanks() throws Exception {
        rows.insertFixture(period);
        assertThat(run(id(1), 2L).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.results(id(1))).containsExactly("1/7/5", "2/9/5");

        JobExecution execution = extend(id(1), 3);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.results(id(1))).containsExactlyElementsOf(FULL_RANKING);
        assertThat(rows.pointer(period)).contains(id(1));
    }

    @Test
    @DisplayName("P7: Step 2 중 실패한 뒤 같은 파라미터로 재실행해 공개하면 결과 checksum·점수·순위가 한 번에 끝난 실행과 같다")
    void restartedRunPublishesSameResult() throws Exception {
        rows.insertFixture(period);
        repository.failScoreOnce(9L);
        assertThat(run(id(1), null).getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution restarted = run(id(1), null);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows.results(id(1))).containsExactlyElementsOf(FULL_RANKING);
        assertThat(rows.pointer(period)).contains(id(1));
        assertThat(publishStep(restarted).getExecutionContext().getString(PublishRankingTasklet.RESULT_CHECKSUM_KEY))
            .isEqualTo(RESULT_CHECKSUM);
    }

    // 다른 공개 TX가 먼저 commit한 상황을 별도 연결(자동 commit)로 만든다.
    private void publishFromOtherConnection(String snapshotId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement insert = connection.prepareStatement(
                "insert into ranking_publication(period_start, snapshot_id) values (?, ?)")) {
                insert.setObject(1, period);
                insert.setString(2, snapshotId);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String id(int revision) {
        return RankingRows.snapshotId(period, revision);
    }

    private JobExecution run(String snapshotId, Long rankingSize) throws Exception {
        JobParametersBuilder parameters = new JobParametersBuilder()
            .addString("periodStart", period.toString())
            .addString("snapshotId", snapshotId);
        if (rankingSize != null) {
            parameters.addLong("rankingSize", rankingSize);
        }
        return jobLauncher.run(weeklyRanking, parameters.toJobParameters());
    }

    private JobExecution extend(String snapshotId, long rankingSize) throws Exception {
        return jobLauncher.run(weeklyRankingExtend, new JobParametersBuilder()
            .addString("snapshotId", snapshotId)
            .addLong("rankingSize", rankingSize)
            .toJobParameters());
    }

    private StepExecution publishStep(JobExecution execution) {
        StepExecution inMemory = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(WeeklyRankingJobConfig.PUBLISH_STEP)).findFirst().orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }
}
