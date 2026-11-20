package com.loopers.batch.job.ranking;

import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.snapshotId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
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
class CalculateScoreStepTest {

    private static final String R1 = snapshotId(1);

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

    @DisplayName("CAL-001, CAL-002, CAL-003: 고정한 입력의 상품마다 quantity 합을 점수로 stage 에 쓴다.")
    @Test
    void writesQuantitySumPerProduct() throws Exception {
        long executionId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.stagedScores(R1)).containsExactly("7:5", "8:1", "9:5")
        );
    }

    @DisplayName("CAL-002: 점수는 고정한 입력에서 구한다. 고정 뒤 원본에 들어온 구매는 점수에 없다.")
    @Test
    void scoresFromFrozenInputNotFromSource() throws Exception {
        fixture.forget(fixture.launch(job, 1));
        fixture.insertActivity(60L, 8L, 4, LocalDateTime.parse("2026-08-20T00:00:00"));
        fixture.jdbc().update("delete from ranking_score_stage where snapshot_id = ?", R1);

        long rerunId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(rerunId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.stagedScores(R1)).containsExactly("7:5", "8:1", "9:5")
        );
    }

    @DisplayName("CAL-003: 같은 입력을 다시 계산해도 상품마다 한 행이고 점수는 합계 그대로다.")
    @Test
    void recalculationConvergesToSameScores() throws Exception {
        fixture.forget(fixture.launch(job, 1));

        long rerunId = fixture.launch(job, 1);

        assertAll(
            () -> assertThat(fixture.statusOf(rerunId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(fixture.stagedScores(R1)).containsExactly("7:5", "8:1", "9:5")
        );
    }
}
