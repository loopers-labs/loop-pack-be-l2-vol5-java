package com.loopers.batch.job.ranking;

import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.snapshotId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.chunk-size=2"
})
@Import(WeeklyRankingJobFixture.class)
@Sql("/sql/purchase-activity.sql")
class PublishRankingStepTest {

    private static final String R1 = snapshotId(1);
    private static final String R2 = snapshotId(2);

    @Autowired
    private WeeklyRankingJobFixture fixture;

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

    @BeforeEach
    void setUp() {
        fixture.emptyTables();
        fixture.insertWeekWithProductsSevenEightNine();
    }

    @AfterEach
    void tearDown() {
        fixture.removeExecutions();
    }

    @DisplayName("PBL-001, PBL-002, RNK-004: 점수 내림차순, 같으면 상품 오름차순으로 순위를 매겨 완성 ranking 에 쓰고 기간의 공개 pointer 를 그 snapshot 으로 둔다.")
    @Test
    void publishesRankedResultAndPointsPeriodToSnapshot() throws Exception {
        long executionId = fixture.launch(job, 1);

        Map<String, Object> publication = fixture.publication();
        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.ranking(R1)).containsExactly("1:7:5", "2:9:5", "3:8:1"),
            () -> assertThat(publication.get("snapshot_id")).isEqualTo(R1),
            () -> assertThat(publication.get("revision")).isEqualTo(1)
        );
    }

    @DisplayName("PBL-001, PBL-003: 점수가 입력의 상품 수보다 적으면 공개하지 않고 실패하며, 기존 공개 pointer 는 그대로다.")
    @Test
    void doesNotPublishIncompleteScores() throws Exception {
        fixture.launch(job, 1);
        Map<String, Object> publishedBefore = fixture.publication();
        fixture.insertActivity(60L, 10L, 4, LocalDateTime.parse("2026-08-20T00:00:00"));
        fixture.launch(new JobBuilder("freezeAndCalculate", jobRepository)
            .start(freezeSnapshotStep).next(calculateScoreStep).build(), 2);
        fixture.jdbc().update("delete from ranking_score_stage where snapshot_id = ? and product_id = 10", R2);

        long executionId = fixture.launch(
            new JobBuilder("publishOnly", jobRepository).start(publishRankingStep).build(), 2
        );

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(fixture.ranking(R2)).isEmpty(),
            () -> assertThat(fixture.publication()).isEqualTo(publishedBefore)
        );
    }

    @DisplayName("PBL-004, PBL-006: 같은 snapshot 을 다시 공개해도 순위 행이 늘지 않고 순위와 공개 시각이 그대로다.")
    @Test
    void republishingSameSnapshotChangesNothing() throws Exception {
        fixture.forget(fixture.launch(job, 1));
        List<String> rankedBefore = fixture.ranking(R1);
        Map<String, Object> publishedBefore = fixture.publication();

        long executionId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.ranking(R1)).isEqualTo(rankedBefore),
            () -> assertThat(fixture.publication()).isEqualTo(publishedBefore)
        );
    }

    @DisplayName("PBL-005: 더 새 revision 이 공개된 뒤 옛 revision 의 공개가 다시 돌아도 pointer 를 되돌리지 않는다.")
    @Test
    void olderRevisionDoesNotMovePointerBack() throws Exception {
        long r1ExecutionId = fixture.launch(job, 1);
        fixture.insertActivity(60L, 10L, 4, LocalDateTime.parse("2026-08-20T00:00:00"));
        fixture.launch(job, 2);
        Map<String, Object> publishedBefore = fixture.publication();
        fixture.forget(r1ExecutionId);

        long executionId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(publishedBefore.get("snapshot_id")).isEqualTo(R2),
            () -> assertThat(fixture.publication()).isEqualTo(publishedBefore)
        );
    }

    @DisplayName("PBL-007: 공개 step 은 순위 순으로 productId, score, rank 를 TAB 으로 잇고 LF 로 끝낸 결과 checksum 을 실행 기록에 남긴다.")
    @Test
    void recordsResultChecksumInStepExecution() throws Exception {
        long executionId = fixture.launch(job, 1);

        Object recorded = fixture.stepNamed(executionId, "publishRanking").getExecutionContext().get("resultChecksum");

        assertThat(recorded).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
    }

    @DisplayName("QRY-004: 공개 pointer 는 가리키는 snapshot 의 입력 고정 시점을 함께 가져, API 가 쓰기 모델을 읽지 않는다.")
    @Test
    void pointerCarriesFrozenAtOfPublishedSnapshot() throws Exception {
        fixture.launch(job, 1);

        assertThat(fixture.publication().get("frozen_at"))
            .isNotNull()
            .isEqualTo(fixture.snapshotInfo(R1).get("frozen_at"));
    }
}
