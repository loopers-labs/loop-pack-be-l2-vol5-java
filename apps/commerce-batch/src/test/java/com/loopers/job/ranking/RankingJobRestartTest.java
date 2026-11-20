package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

class RankingJobRestartTest extends WeeklyRankingJobTestSupport {
    private static final List<List<Long>> EXPECTED_RANKING = List.of(
            List.of(7L, 5L, 1L), List.of(9L, 5L, 2L), List.of(8L, 1L, 3L));
    private static final String EXPECTED_RESULT = "7\t5\t1\n9\t5\t2\n8\t1\t3\n";
    private static final String EXPECTED_INPUT = ""
            + "42\t7\t2\t2026-08-17T01:00:00.000000Z\n"
            + "42\t8\t1\t2026-08-17T01:00:00.000000Z\n"
            + "43\t7\t3\t2026-08-18T01:00:00.000000Z\n"
            + "44\t9\t5\t2026-08-19T01:00:00.000000Z\n";

    private JobExecution previousPublication;
    private JobExecution first;
    private final AtomicBoolean failBeforeNine = new AtomicBoolean(true);

    @BeforeEach
    void failAtProductNine() throws Exception {
        database.insertWeek20260817Fixture();
        previousPublication = launch(snapshot(1));

        doAnswer(invocation -> {
            long productId = invocation.getArgument(1);
            if (productId == 9L && failBeforeNine.get()) {
                throw new IllegalStateException("injected failure before product 9");
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).productTotal(any(SnapshotId.class), anyLong());

        first = launch(snapshot(2));
    }

    @Test @DisplayName("기존 공개본을 만드는 실행은 완료된다")
    void previousPublicationCompletes() {
        assertThat(previousPublication.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test @DisplayName("상품 9에서 첫 실행이 실패한다")
    void firstExecutionFails() {
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test @DisplayName("첫 실행은 입력 고정 후 계산 단계에서 실패한다")
    void firstExecutionReachesCalculation() {
        assertThat(executedSteps(first))
                .containsExactly(WeeklyRankingJobConfig.FREEZE_STEP, WeeklyRankingJobConfig.CALCULATE_STEP);
    }

    @Test @DisplayName("첫 계산 Step은 실패 상태로 기록된다")
    void firstCalculationFails() {
        assertThat(persistedStep(first, WeeklyRankingJobConfig.CALCULATE_STEP).getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test @DisplayName("실패 전 마지막 커밋 위치는 2다")
    void checkpointAfterFailureIsTwo() {
        String checkpointKey = WeeklyRankingJobConfig.PRODUCT_READER + ".read.count";
        StepExecution failedCalculation = persistedStep(first, WeeklyRankingJobConfig.CALCULATE_STEP);
        assertThat(failedCalculation.getExecutionContext().getInt(checkpointKey)).isEqualTo(2);
    }

    @Test @DisplayName("실패 후 상품 7과 8의 점수는 남는다")
    void committedScoresRemainAfterFailure() {
        assertThat(stageScores(snapshot(2))).isEqualTo(Map.of(7L, 5L, 8L, 1L));
    }

    @Test @DisplayName("계산 실패 후 새 랭킹은 공개 결과 테이블에 없다")
    void failedExecutionHasNoRanking() {
        assertThat(ranking(snapshot(2))).isEmpty();
    }

    @Test @DisplayName("계산 실패 후 기존 정상 결과가 공개된 채 남는다")
    void previousPublicationRemainsAfterFailure() {
        assertThat(publishedSnapshot(WEEK)).contains(snapshot(1));
    }

    @Nested
    class AfterRestart {
        private JobExecution restart;
        private Object frozenAtBeforeRestart;

        @BeforeEach
        void restartWithTheSameSnapshot() throws Exception {
            frozenAtBeforeRestart = manifest(snapshot(2)).get("frozen_at");
            failBeforeNine.set(false);
            restart = launch(snapshot(2));
        }

        @Test @DisplayName("재시작은 같은 JobInstance를 사용한다")
        void restartUsesSameJobInstance() {
            assertThat(restart.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        }

        @Test @DisplayName("재시작은 새로운 JobExecution을 만든다")
        void restartCreatesNewExecution() {
            assertThat(restart.getId()).isNotEqualTo(first.getId());
        }

        @Test @DisplayName("재시작은 완료된다")
        void restartCompletes() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test @DisplayName("재시작은 입력 고정 단계를 건너뛰고 계산과 공개를 실행한다")
        void restartRunsCalculationAndPublication() {
            assertThat(executedSteps(restart))
                    .containsExactly(WeeklyRankingJobConfig.CALCULATE_STEP, WeeklyRankingJobConfig.PUBLISH_STEP);
        }

        @Test @DisplayName("재시작은 남은 상품 하나만 읽는다")
        void restartReadsOneProduct() {
            StepExecution resumedCalculation = persistedStep(restart, WeeklyRankingJobConfig.CALCULATE_STEP);
            assertThat(resumedCalculation.getReadCount()).isEqualTo(1L);
        }

        @Test @DisplayName("재시작은 남은 상품 하나만 저장한다")
        void restartWritesOneProduct() {
            StepExecution resumedCalculation = persistedStep(restart, WeeklyRankingJobConfig.CALCULATE_STEP);
            assertThat(resumedCalculation.getWriteCount()).isEqualTo(1L);
        }

        @Test @DisplayName("재시작 후에도 고정 입력은 네 행이다")
        void restartKeepsFourFrozenRows() {
            assertThat(inputCount(snapshot(2))).isEqualTo(4);
        }

        @Test @DisplayName("재시작해도 입력 고정 시각은 바뀌지 않는다")
        void restartKeepsFrozenAt() {
            assertThat(manifest(snapshot(2)).get("frozen_at")).isEqualTo(frozenAtBeforeRestart);
        }

        @Test @DisplayName("재시작 결과는 기대한 순위와 같다")
        void restartedRankingMatchesExpected() {
            assertThat(ranking(snapshot(2))).isEqualTo(EXPECTED_RANKING);
        }

        @Test @DisplayName("재시작 성공 후 새 결과가 공개된다")
        void restartPublishesNewSnapshot() {
            assertThat(publishedSnapshot(WEEK)).contains(snapshot(2));
        }

        @Test @DisplayName("재시작 결과 체크섬은 기대한 랭킹의 체크섬과 같다")
        void restartedResultChecksumMatchesExpected() throws Exception {
            assertThat(manifest(snapshot(2)).get("result_checksum")).isEqualTo(sha256(EXPECTED_RESULT));
        }

        @Test @DisplayName("재시작 입력 체크섬은 기대한 입력의 체크섬과 같다")
        void restartedInputChecksumMatchesExpected() throws Exception {
            assertThat(manifest(snapshot(2)).get("input_checksum")).isEqualTo(sha256(EXPECTED_INPUT));
        }
    }

    private static String sha256(String serialized) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(StandardCharsets.UTF_8)));
    }
}
