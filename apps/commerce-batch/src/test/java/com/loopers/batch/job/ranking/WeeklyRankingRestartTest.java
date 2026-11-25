package com.loopers.batch.job.ranking;

import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.params;
import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.snapshotId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
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
class WeeklyRankingRestartTest {

    private static final String R1 = snapshotId(1);
    private static final String R2 = snapshotId(2);
    private static final String HOLD_PRODUCT_NINE =
        "insert into ranking_score_stage (snapshot_id, product_id, score) values (?, 9, 0)";

    @Autowired
    private WeeklyRankingJobFixture fixture;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @BeforeEach
    void setUp() {
        fixture.emptyTables();
        fixture.insertWeekWithProductsSevenEightNine();
    }

    @AfterEach
    void tearDown() {
        fixture.removeExecutions();
    }

    @DisplayName("JOB-004, EXP-026: 계산이 상품 9 에서 실패하면, 같은 parameter 의 재실행이 같은 instance 에서 고정 step 을 다시 돌지 않고 저장된 위치 다음 상품부터 이어 간다.")
    @Test
    void resumesCalculationFromLastCommittedProduct() throws Exception {
        long firstId = fixture.launchWhileHolding(HOLD_PRODUCT_NINE, R1, job, params(1));

        JobExecution first = fixture.execution(firstId);
        assertAll(
            () -> assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(fixture.stepNamed(firstId, "calculateScore").getExecutionContext()
                .get("snapshotProductReader.start.after")).hasToString("{product_id=8}"),
            () -> assertThat(fixture.stagedScores(R1)).containsExactly("7:5", "8:1")
        );

        long secondId = fixture.launch(job, 1);

        JobExecution second = fixture.execution(secondId);
        assertAll(
            () -> assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(second.getJobInstance().getInstanceId())
                .isEqualTo(first.getJobInstance().getInstanceId()),
            () -> assertThat(secondId).isNotEqualTo(firstId),
            () -> assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("calculateScore", "publishRanking"),
            () -> assertThat(fixture.stepNamed(secondId, "calculateScore").getWriteCount()).isEqualTo(1),
            () -> assertThat(fixture.stagedScores(R1)).containsExactly("7:5", "8:1", "9:5")
        );
    }

    @DisplayName("EXP-026, PBL-003, REV-003: 새 revision 의 계산이 실패한 동안 기존 공개본이 그대로 보이고, 재실행이 끝나면 새 revision 이 공개된다.")
    @Test
    void keepsPublishedRevisionWhileNewRevisionFails() throws Exception {
        fixture.launch(job, 1);
        Map<String, Object> publishedBefore = fixture.publication();

        long failedId = fixture.launchWhileHolding(HOLD_PRODUCT_NINE, R2, job, params(2));

        assertAll(
            () -> assertThat(fixture.statusOf(failedId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(publishedBefore.get("snapshot_id")).isEqualTo(R1),
            () -> assertThat(fixture.publication()).isEqualTo(publishedBefore),
            () -> assertThat(fixture.ranking(R2)).isEmpty()
        );

        long rerunId = fixture.launch(job, 2);

        assertAll(
            () -> assertThat(fixture.statusOf(rerunId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.publication().get("snapshot_id")).isEqualTo(R2),
            () -> assertThat(fixture.ranking(R2)).hasSize(3)
        );
    }

    @DisplayName("EXP-027: 한 번에 끝난 실행과 실패 뒤 재시작으로 끝난 실행의 입력 checksum, 결과 checksum, 순위가 같다.")
    @Test
    void restartedRunMatchesUninterruptedRun() throws Exception {
        long normalId = fixture.launch(job, 1);

        long failedId = fixture.launchWhileHolding(HOLD_PRODUCT_NINE, R2, job, params(2));
        long restartedId = fixture.launch(job, 2);

        assertAll(
            () -> assertThat(fixture.statusOf(normalId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.statusOf(failedId)).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(fixture.execution(restartedId).getJobInstance().getInstanceId())
                .isEqualTo(fixture.execution(failedId).getJobInstance().getInstanceId()),
            () -> assertThat(fixture.statusOf(restartedId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.snapshotInfo(R2).get("input_checksum"))
                .isEqualTo(fixture.snapshotInfo(R1).get("input_checksum")),
            () -> assertThat(resultChecksum(restartedId)).isEqualTo(resultChecksum(normalId)),
            () -> assertThat(fixture.ranking(R2)).isEqualTo(fixture.ranking(R1))
                .containsExactly("1:7:5", "2:9:5", "3:8:1")
        );
    }

    private Object resultChecksum(long executionId) {
        return fixture.stepNamed(executionId, "publishRanking").getExecutionContext().get("resultChecksum");
    }
}
