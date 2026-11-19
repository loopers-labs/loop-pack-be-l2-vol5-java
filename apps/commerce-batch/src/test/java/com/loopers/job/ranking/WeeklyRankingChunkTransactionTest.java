package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.domain.ProductScore;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

class WeeklyRankingChunkTransactionTest extends WeeklyRankingJobTestSupport {

    @Nested
    class WriterFailsAfterSaving {
        private JobExecution failed;
        private JobExecution restart;
        private Map<Long, Long> scoresAfterFailure;
        private int checkpointAfterFailure;

        @BeforeEach
        void failAfterWritingThenRestart() throws Exception {
            database.insertWeek20260817Fixture();
            var failAfterWrite = new AtomicBoolean(true);
            doAnswer(invocation -> {
                invocation.callRealMethod();
                if (failAfterWrite.getAndSet(false)) {
                    throw new IllegalStateException("injected failure after stage write");
                }
                return null;
            }).when(scoreStageStore).upsert(any(SnapshotId.class), anyList());

            failed = launch(snapshot(1));
            scoresAfterFailure = stageScores(snapshot(1));
            String checkpointKey = WeeklyRankingJobConfig.PRODUCT_READER + ".read.count";
            checkpointAfterFailure = persistedStep(failed, WeeklyRankingJobConfig.CALCULATE_STEP)
                    .getExecutionContext().getInt(checkpointKey);
            restart = launch(snapshot(1));
        }

        @Test
        @DisplayName("Writer 실패 시 첫 실행이 실패한다")
        void firstExecutionFails() {
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("Writer가 저장한 첫 청크 점수는 롤백된다")
        void firstChunkScoresRollBack() {
            assertThat(scoresAfterFailure).isEmpty();
        }

        @Test
        @DisplayName("첫 청크 체크포인트도 0으로 롤백된다")
        void firstChunkCheckpointRollsBack() {
            assertThat(checkpointAfterFailure).isZero();
        }

        @Test
        @DisplayName("같은 스냅샷을 재시작하면 완료된다")
        void restartCompletes() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("재시작은 세 상품의 점수를 모두 저장한다")
        void restartStoresAllScores() {
            assertThat(stageScores(snapshot(1))).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
        }
    }

    @DisplayName("같은 상품의 점수를 다시 쓰면 더하지 않고 마지막 절대값으로 바꾼다")
    @Test
    void writingScoreAgainReplacesInsteadOfAdding() {
        SnapshotId id = WeeklyRankingParameters.of(WEEK, snapshot(1)).snapshotId();

        scoreStageStore.upsert(id, List.of(new ProductScore(7, 3)));
        scoreStageStore.upsert(id, List.of(new ProductScore(7, 5)));
        scoreStageStore.upsert(id, List.of(new ProductScore(7, 5)));

        assertThat(stageScores(snapshot(1))).isEqualTo(Map.of(7L, 5L));
    }
}
