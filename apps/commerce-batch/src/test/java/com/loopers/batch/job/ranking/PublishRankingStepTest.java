package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.chunk-size=2"
})
@Sql("/sql/purchase-activity.sql")
class PublishRankingStepTest {

    private static final String SNAPSHOT_ID = "weekly-20260817-r1";
    private static final String R2 = "weekly-20260817-r2";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    @Qualifier("freezeSnapshotStep")
    private Step freezeSnapshotStep;

    @Autowired
    @Qualifier("calculateScoreStep")
    private Step calculateScoreStep;

    @Autowired
    @Qualifier("publishRankingStep")
    private Step publishRankingStep;

    private JdbcTemplate jdbc;

    private final List<Long> executionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("truncate table purchase_activity");
        jdbc.execute("truncate table ranking_snapshot");
        jdbc.execute("truncate table ranking_snapshot_input");
        jdbc.execute("truncate table ranking_score_stage");
        jdbc.execute("truncate table weekly_ranking");
        jdbc.execute("truncate table weekly_ranking_publication");

        insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(42L, 8L, 1, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(43L, 7L, 3, LocalDateTime.parse("2026-08-18T01:00:00"));
        insertActivity(44L, 9L, 5, LocalDateTime.parse("2026-08-19T01:00:00"));
    }

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            executionIds.stream().map(jobExplorer::getJobExecution).toList()
        );
    }

    @DisplayName("PBL-001, PBL-002, RNK-004: 점수 내림차순, 같으면 상품 오름차순으로 순위를 매겨 완성 ranking 에 쓰고 기간의 공개 pointer 를 그 snapshot 으로 둔다.")
    @Test
    void publishesRankedResultAndPointsPeriodToSnapshot() throws Exception {
        long executionId = launch();

        Map<String, Object> publication = jdbc.queryForMap(
            "select snapshot_id, revision from weekly_ranking_publication where period_start = '2026-08-17'"
        );
        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(publishedRanking()).containsExactly("1:7:5", "2:9:5", "3:8:1"),
            () -> assertThat(publication.get("snapshot_id")).isEqualTo(SNAPSHOT_ID),
            () -> assertThat(publication.get("revision")).isEqualTo(1)
        );
    }

    @DisplayName("PBL-001, PBL-003: 점수가 입력의 상품 수보다 적으면 공개하지 않고 실패하며, 기존 공개 pointer 는 그대로다.")
    @Test
    void doesNotPublishIncompleteScores() throws Exception {
        launch(job, 1);
        Map<String, Object> publishedBefore = publication();
        insertActivity(60L, 10L, 4, LocalDateTime.parse("2026-08-20T00:00:00"));
        launch(new JobBuilder("freezeAndCalculate", jobRepository)
            .start(freezeSnapshotStep).next(calculateScoreStep).build(), 2);
        jdbc.update("delete from ranking_score_stage where snapshot_id = ? and product_id = 10", R2);

        long executionId = launch(new JobBuilder("publishOnly", jobRepository).start(publishRankingStep).build(), 2);

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(publishedRanking(R2)).isEmpty(),
            () -> assertThat(publication()).isEqualTo(publishedBefore)
        );
    }

    @DisplayName("PBL-004, PBL-006: 같은 snapshot 을 다시 공개해도 순위 행이 늘지 않고 순위와 공개 시각이 그대로다.")
    @Test
    void republishingSameSnapshotChangesNothing() throws Exception {
        forget(launch(job, 1));
        List<String> rankedBefore = publishedRanking();
        Map<String, Object> publishedBefore = publication();

        long executionId = launch(job, 1);

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(publishedRanking()).isEqualTo(rankedBefore),
            () -> assertThat(publication()).isEqualTo(publishedBefore)
        );
    }

    @DisplayName("PBL-005: 더 새 revision 이 공개된 뒤 옛 revision 의 공개가 다시 돌아도 pointer 를 되돌리지 않는다.")
    @Test
    void olderRevisionDoesNotMovePointerBack() throws Exception {
        long r1ExecutionId = launch(job, 1);
        insertActivity(60L, 10L, 4, LocalDateTime.parse("2026-08-20T00:00:00"));
        launch(job, 2);
        Map<String, Object> publishedBefore = publication();
        forget(r1ExecutionId);

        long executionId = launch(job, 1);

        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(publishedBefore.get("snapshot_id")).isEqualTo(R2),
            () -> assertThat(publication()).isEqualTo(publishedBefore)
        );
    }

    @DisplayName("PBL-007: 공개 step 은 순위 순으로 productId, score, rank 를 TAB 으로 잇고 LF 로 끝낸 결과 checksum 을 실행 기록에 남긴다.")
    @Test
    void recordsResultChecksumInStepExecution() throws Exception {
        long executionId = launch();

        JobExecution execution = jobExplorer.getJobExecution(executionId);
        StepExecution publish = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals("publishRanking"))
            .findFirst()
            .orElseThrow();
        Object recorded = jobExplorer.getStepExecution(executionId, publish.getId())
            .getExecutionContext().get("resultChecksum");

        assertThat(recorded).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
    }

    private void forget(long executionId) {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            List.of(jobExplorer.getJobExecution(executionId))
        );
        executionIds.remove(executionId);
    }

    private Map<String, Object> publication() {
        return jdbc.queryForMap(
            "select snapshot_id, revision, published_at from weekly_ranking_publication "
                + "where period_start = '2026-08-17'"
        );
    }

    private long launch() throws Exception {
        return launch(job, 1);
    }

    private long launch(Job target, int revision) throws Exception {
        JobExecution execution = jobLauncher.run(target, new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
            .addLong("revision", (long) revision)
            .addString("snapshotId", "weekly-20260817-r" + revision)
            .toJobParameters());
        executionIds.add(execution.getId());
        return execution.getId();
    }

    private BatchStatus statusOf(long executionId) {
        return jobExplorer.getJobExecution(executionId).getStatus();
    }

    private List<String> publishedRanking() {
        return publishedRanking(SNAPSHOT_ID);
    }

    private List<String> publishedRanking(String snapshotId) {
        return jdbc.query(
            "select rank_no, product_id, score from weekly_ranking where snapshot_id = ? order by rank_no",
            (rs, rowNum) -> rs.getInt("rank_no") + ":" + rs.getLong("product_id") + ":" + rs.getLong("score"),
            snapshotId
        );
    }

    private void insertActivity(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        jdbc.update(
            "insert into purchase_activity "
                + "(created_at, updated_at, order_id, product_id, quantity, confirmed_at) "
                + "values (now(6), now(6), ?, ?, ?, ?)",
            orderId, productId, quantity, confirmedAtUtc
        );
    }
}
