package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.ProductKey;
import com.loopers.batch.job.ranking.step.ProductScore;
import com.loopers.batch.job.ranking.step.WeeklyScoreProcessor;
import com.loopers.batch.job.ranking.step.WeeklyScoreWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * 실습 §6 의 재시작. chunk=2 로 상품 7 · 8 을 commit 한 뒤 상품 9 직전에 한 번 실패시키고, 같은 식별 파라미터로 다시 실행한다.
 * 실패 스위치는 파라미터가 아니라 processor spy 로 건다
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "weekly-ranking.chunk-size=2"
})
class WeeklyRankingRestartTest {

    private static final LocalDate WEEK = LocalDate.parse("2026-10-05");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @MockitoSpyBean
    private WeeklyScoreProcessor processor;

    @Autowired
    private WeeklyScoreWriter writer;

    @Autowired
    private RankingTables tables;

    @AfterEach
    void tearDown() {
        tables.truncate();
    }

    @DisplayName("상품 9 직전에 실패한 뒤 같은 파라미터로 다시 실행하면, 같은 JobInstance 가 입력을 다시 고정하지 않고 상품 9 부터 이어 가 정상 실행과 같은 점수를 낸다.")
    @Test
    void resumesFromLastCommittedChunkWithSameResult() throws Exception {
        // arrange: 비교용 정상 실행은 같은 입력의 다른 revision 으로 둔다
        tables.confirmLabFixture(WEEK);
        WeeklyRankingParameters normal = WeeklyRankingParameters.of(WEEK, 1);
        WeeklyRankingParameters restarted = WeeklyRankingParameters.of(WEEK, 2);
        assertThat(jobLauncher.run(job, normal.toJobParameters()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        failBeforeProduct(9L);

        // act
        JobExecution first = jobLauncher.run(job, restarted.toJobParameters());

        // assert: 첫 chunk(7, 8)와 reader 위치 2 만 commit 되고, 고정 입력은 남는다
        StepExecution firstScoreStep = reloadStep(first, WeeklyRankingJobConfig.SCORE_STEP);
        assertAll(
            () -> assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(firstScoreStep.getExecutionContext().getInt(WeeklyRankingJobConfig.PRODUCT_READER + ".read.count")).isEqualTo(2),
            () -> assertThat(firstScoreStep.getCommitCount()).isEqualTo(1),
            () -> assertThat(tables.scoresOf(restarted.snapshotId()))
                .extracting(row -> row.get("product_id"), row -> row.get("score"), row -> row.get("ranking"))
                .containsExactly(tuple(7L, 5L, null), tuple(8L, 1L, null)),
            () -> assertThat(tables.manifestOf(restarted.snapshotId())).containsEntry("status", "FROZEN"),
            () -> assertThat(tables.inputsOf(restarted.snapshotId())).hasSize(4)
        );

        // act: 실패 스위치를 끄고 job · reader 객체를 새로 받아 같은 파라미터로 실행
        Mockito.reset(processor);
        JobExecution second = jobLauncher.run(job, restarted.toJobParameters());

        // assert
        StepExecution secondScoreStep = reloadStep(second, WeeklyRankingJobConfig.SCORE_STEP);
        assertAll(
            () -> assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId()),
            () -> assertThat(second.getId()).isNotEqualTo(first.getId()),
            () -> assertThat(stepNames(second)).doesNotContain(WeeklyRankingJobConfig.FREEZE_STEP),
            () -> assertThat(secondScoreStep.getReadCount()).isEqualTo(1),
            () -> assertThat(secondScoreStep.getWriteCount()).isEqualTo(1),
            // 손으로 계산한 기대값: 7 은 5점 1위, 9 는 동점이라 productId 순으로 2위, 8 은 1점 3위
            () -> assertThat(tables.scoresOf(restarted.snapshotId()))
                .extracting(row -> row.get("product_id"), row -> row.get("score"), row -> row.get("ranking"))
                .containsExactly(tuple(7L, 5L, 1), tuple(8L, 1L, 3), tuple(9L, 5L, 2)),
            () -> assertThat(tables.resultChecksumOf(restarted.snapshotId()))
                .isEqualTo(tables.resultChecksumOf(normal.snapshotId()))
                .isEqualTo(RankingTables.sha256("7\t5\t1\n9\t5\t2\n8\t1\t3\n")),
            () -> assertThat(tables.manifestOf(restarted.snapshotId()).get("input_checksum"))
                .isEqualTo(tables.manifestOf(normal.snapshotId()).get("input_checksum")),
            () -> assertThat(tables.manifestOf(restarted.snapshotId())).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.manifestOf(normal.snapshotId())).containsEntry("status", "COMPLETED"),
            () -> assertThat(tables.inputsOf(restarted.snapshotId())).hasSize(4)
        );
    }

    @DisplayName("포기(ABANDONED)로 표시한 snapshot 은 같은 파라미터로 다시 실행해도 점수 계산을 이어 가지 않는다.")
    @Test
    void doesNotResumeAbandonedSnapshot() throws Exception {
        // arrange
        tables.confirm(80L, "2026-09-15 01:00:00", Map.of(7L, 2, 8L, 1, 9L, 5));
        WeeklyRankingParameters params = WeeklyRankingParameters.of(LocalDate.parse("2026-09-14"), 1);
        failBeforeProduct(9L);
        jobLauncher.run(job, params.toJobParameters());
        tables.changeStatus(params.snapshotId(), "ABANDONED");
        Mockito.reset(processor);

        // act
        JobExecution resumed = jobLauncher.run(job, params.toJobParameters());

        // assert
        assertAll(
            () -> assertThat(resumed.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(reloadStep(resumed, WeeklyRankingJobConfig.SCORE_STEP).getExitStatus().getExitDescription()).contains("포기"),
            () -> assertThat(tables.scoresOf(params.snapshotId())).extracting(row -> row.get("product_id")).containsExactly(7L, 8L)
        );
    }

    @DisplayName("같은 상품의 점수를 두 번 써도 더해지지 않고 같은 절대값으로 남는다.")
    @Test
    void writerConvergesToAbsoluteScore() throws Exception {
        // arrange
        Chunk<ProductScore> chunk = Chunk.of(new ProductScore("weekly-20260921-r1", 7L, 5L));

        // act
        writer.write(chunk);
        writer.write(chunk);

        // assert
        assertThat(tables.scoresOf("weekly-20260921-r1"))
            .extracting(row -> row.get("product_id"), row -> row.get("score"))
            .containsExactly(tuple(7L, 5L));
    }

    private void failBeforeProduct(long productId) throws Exception {
        doThrow(new IllegalStateException("before product " + productId))
            .when(processor).process(argThat((ProductKey key) -> key != null && key.productId() == productId));
    }

    /** 메모리의 실행 객체가 아니라 저장된 metadata 를 다시 읽는다 */
    private StepExecution reloadStep(JobExecution execution, String stepName) {
        StepExecution step = execution.getStepExecutions().stream()
            .filter(each -> each.getStepName().equals(stepName))
            .findFirst()
            .orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), step.getId());
    }

    private List<String> stepNames(JobExecution execution) {
        return execution.getStepExecutions().stream().map(StepExecution::getStepName).toList();
    }
}
