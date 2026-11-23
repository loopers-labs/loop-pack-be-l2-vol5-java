package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.domain.InvalidPurchaseInputException;
import com.loopers.batch.job.ranking.domain.SnapshotConflictException;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.domain.SnapshotManifest;
import com.loopers.batch.job.ranking.domain.SnapshotStatus;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

class WeeklyRankingFreezeTest extends WeeklyRankingJobTestSupport {

    @Test
    @DisplayName("상품 9에서 실패해도 첫 청크의 상품 7과 8 점수는 저장된다")
    void firstChunkScoresRemainAfterProductNineFails() throws Exception {
        database.insertActivity(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
        database.insertActivity(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));
        doAnswer(invocation -> {
            if (invocation.<Long>getArgument(1) == 9L) {
                throw new IllegalStateException("injected failure before product 9");
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).productTotal(any(SnapshotId.class), anyLong());

        launch(snapshot(1));

        assertThat(stageScores(snapshot(1))).isEqualTo(Map.of(7L, 5L, 8L, 1L));
    }

    @Test
    @DisplayName("상품 9에서 실패하면 첫 청크의 읽기 위치 2가 저장된다")
    void firstChunkCheckpointRemainsAfterProductNineFails() throws Exception {
        database.insertActivity(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
        database.insertActivity(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));
        doAnswer(invocation -> {
            if (invocation.<Long>getArgument(1) == 9L) {
                throw new IllegalStateException("injected failure before product 9");
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).productTotal(any(SnapshotId.class), anyLong());

        JobExecution failed = launch(snapshot(1));
        var calculation = persistedStep(failed, WeeklyRankingJobConfig.CALCULATE_STEP);
        String checkpointKey = WeeklyRankingJobConfig.PRODUCT_READER + ".read.count";

        assertThat(calculation.getExecutionContext().getInt(checkpointKey)).isEqualTo(2);
    }

    @Nested
    class WeekBoundary {
        private JobExecution execution;

        @BeforeEach
        void createActivitiesAroundWeekBoundary() throws Exception {
            database.insertActivity(1, 100, 1, Instant.parse("2026-08-16T23:59:59.999999Z"));
            database.insertActivity(2, 101, 2, Instant.parse("2026-08-17T00:00:00Z"));
            database.insertActivity(3, 102, 3, Instant.parse("2026-08-23T23:59:59.999999Z"));
            database.insertActivity(3, 103, 4, Instant.parse("2026-08-23T23:59:59.999999Z"));
            database.insertActivity(4, 104, 5, Instant.parse("2026-08-24T00:00:00Z"));
            execution = launch(snapshot(1));
        }
        @Test
        @DisplayName("경계 입력을 고정한 Job이 완료된다")
        void completes() {
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("주의 시작은 포함하고 끝은 제외하며 주문의 여러 상품을 모두 고정한다")
        void freezesWeekOnly() {
            assertThat(jdbc.queryForList("SELECT product_id FROM ranking_snapshot_input WHERE snapshot_id = ? " + "ORDER BY product_id", Long.class, snapshot(1))).containsExactly(101L, 102L, 103L);
        }

        @Test
        @DisplayName("고정 입력 세 행이 기록된다")
        void countsInput() {
            assertThat(manifest(snapshot(1)).get("input_count")).isEqualTo(3);
        }

        @Test
        @DisplayName("서로 다른 상품 세 개가 기록된다")
        void countsProducts() {
            assertThat(manifest(snapshot(1)).get("product_count")).isEqualTo(3);
        }

    }

    @Nested
    class LatePurchaseAfterFreeze {
        private JobExecution failed;
        private JobExecution restart;
        private JobExecution revision;
        private int oldInputCount;
        private List<List<Long>> oldRanking;

        @BeforeEach
        void latePurchaseArrivesAfterSnapshotWasFrozen() throws Exception {
            database.insertWeek20260817Fixture();
            var failOnce = new AtomicBoolean(true);
            doAnswer(invocation -> {
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("injected calculation failure");
                }
                return invocation.callRealMethod();
            }).when(snapshotStore).productTotal(any(SnapshotId.class), anyLong());

            failed = launch(snapshot(1));
            database.insertActivity(45, 8, 10, Instant.parse("2026-08-20T01:00:00Z"));
            restart = launch(snapshot(1));
            oldInputCount = inputCount(snapshot(1));
            oldRanking = ranking(snapshot(1));
            revision = launch(snapshot(2));
        }
        @Test
        @DisplayName("첫 계산은 실패한다")
        void firstFails() {
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("기존 스냅샷으로 재시작하면 완료된다")
        void restartCompletes() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("늦은 구매가 들어와도 기존 입력은 네 행이다")
        void oldInputStaysFrozen() {
            assertThat(oldInputCount).isEqualTo(4);
        }

        @Test
        @DisplayName("기존 랭킹에는 늦은 구매가 반영되지 않는다")
        void oldRankingExcludesLatePurchase() {
            assertThat(oldRanking).isEqualTo(List.of(List.of(7L, 5L, 1L), List.of(9L, 5L, 2L), List.of(8L, 1L, 3L)));
        }

        @Test
        @DisplayName("새 revision 계산이 완료된다")
        void newRevisionCompletes() {
            assertThat(revision.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("새 스냅샷에는 늦은 구매를 포함한 다섯 행이 있다")
        void newInputIncludesLatePurchase() {
            assertThat(inputCount(snapshot(2))).isEqualTo(5);
        }

        @Test
        @DisplayName("새 랭킹에는 늦은 구매가 반영된다")
        void newRankingIncludesLatePurchase() {
            assertThat(ranking(snapshot(2))).isEqualTo(List.of(List.of(8L, 11L, 1L), List.of(7L, 5L, 2L), List.of(9L, 5L, 3L)));
        }

        @Test
        @DisplayName("새 공개 이후에도 기존 랭킹 세 행이 남는다")
        void oldRankingRemains() {
            assertThat(ranking(snapshot(1))).hasSize(3);
        }

        @Test
        @DisplayName("새 revision이 공개된다")
        void newRevisionIsPublished() {
            assertThat(publishedSnapshot(WEEK)).contains(snapshot(2));
        }

    }

    @DisplayName("고정 입력이 바뀌면 같은 점수라도 재시작 시 충돌로 실패한다")
    @Test
    void restartRejectsModifiedFrozenInput() throws Exception {
        database.insertActivity(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
        database.insertActivity(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));
        var failBeforeNine = new AtomicBoolean(true);
        doAnswer(invocation -> {
            long productId = invocation.getArgument(1);
            if (productId == 9L && failBeforeNine.getAndSet(false)) {
                throw new IllegalStateException("injected failure before product 9");
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).productTotal(any(SnapshotId.class), anyLong());

        launch(snapshot(1));
        // 상품 7의 합계 5는 유지하면서 주문별 수량을 바꾼다.
        jdbc.update("UPDATE ranking_snapshot_input SET quantity = CASE order_id WHEN 42 THEN 3 ELSE 2 END "
                + "WHERE snapshot_id = ? AND product_id = 7", snapshot(1));

        JobExecution restart = launch(snapshot(1));

        assertThat(restart.getAllFailureExceptions()).anyMatch(SnapshotConflictException.class::isInstance);
    }

    @Nested
    class FreezeFailsBeforeManifest {
        private JobExecution first;
        private JobExecution restart;
        private int inputRowsAfterFailure;
        private Integer manifestsAfterFailure;

        @BeforeEach
        void failBeforeManifestAndRestart() throws Exception {
            database.insertWeek20260817Fixture();
            var failOnce = new AtomicBoolean(true);
            doAnswer(invocation -> {
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("injected failure before manifest");
                }
                return invocation.callRealMethod();
            }).when(snapshotStore).insertManifest(any(SnapshotManifest.class));
            first = launch(snapshot(1));
            inputRowsAfterFailure = inputCount(snapshot(1));
            manifestsAfterFailure = jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot", Integer.class);
            restart = launch(snapshot(1));
        }
        @Test
        @DisplayName("manifest 저장 실패 시 첫 실행이 실패한다")
        void firstFails() {
            assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("첫 실행은 입력 고정 단계에서 실패한다")
        void onlyFreezeRuns() {
            assertThat(executedSteps(first)).containsExactly(WeeklyRankingJobConfig.FREEZE_STEP);
        }

        @Test
        @DisplayName("실패 후 반쪽 스냅샷 입력이 남지 않는다")
        void noPartialInput() {
            assertThat(inputRowsAfterFailure).isZero();
        }

        @Test
        @DisplayName("실패 후 manifest가 남지 않는다")
        void noManifest() {
            assertThat(manifestsAfterFailure).isZero();
        }

        @Test
        @DisplayName("재시작하면 완료된다")
        void restartCompletes() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("재시작하면 입력 네 행을 고정한다")
        void restartFreezesAllInput() {
            assertThat(inputCount(snapshot(1))).isEqualTo(4);
        }

    }

    @Nested
    class FrozenInputAlreadyCommitted {
        private JobExecution execution;

        @BeforeEach
        void restartAfterInputCommittedButStepCompletionWasLost() throws Exception {
            database.insertWeek20260817Fixture();
            var id = WeeklyRankingParameters.of(WEEK, snapshot(1)).snapshotId();
            snapshotStore.copyActivities(id);
            snapshotStore.insertManifest(SnapshotManifest.frozen(id,
                    LocalDateTime.parse("2026-08-24T00:10:00"), snapshotStore.summarizeInput(id)));
            execution = launch(snapshot(1));
        }
        @Test
        @DisplayName("기존 입력을 사용해 Job이 완료된다")
        void completes() {
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("이미 고정된 입력을 다시 복사하지 않는다")
        void doesNotCopyAgain() {
            assertThat(persistedStep(execution, WeeklyRankingJobConfig.FREEZE_STEP).getWriteCount()).isZero();
        }

        @Test
        @DisplayName("기존 입력 네 행을 유지한다")
        void keepsInput() {
            assertThat(inputCount(snapshot(1))).isEqualTo(4);
        }

        @Test
        @DisplayName("기존 입력으로 상품 세 개의 랭킹을 만든다")
        void createsRanking() {
            assertThat(ranking(snapshot(1))).hasSize(3);
        }

    }

    @Nested
    class ConflictingSnapshot {
        private JobExecution execution;

        @BeforeEach
        void createManifestThatConflictsWithStoredInput() throws Exception {
            database.insertWeek20260817Fixture();
            jdbc.update("INSERT INTO ranking_snapshot (snapshot_id, period_start, period_end, revision, status, "
                    + "frozen_at, input_count, product_count, input_checksum) VALUES (?, '2026-08-17', '2026-08-24', 1, "
                    + "'FROZEN', '2026-08-24 00:00:00', 9, 9, ?)", snapshot(1), "0".repeat(64));
            execution = launch(snapshot(1));
        }
        @Test
        @DisplayName("입력이 manifest와 다르면 Job이 실패한다")
        void fails() {
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("입력 불일치를 충돌로 보고한다")
        void reportsConflict() {
            assertThat(execution.getAllFailureExceptions()).anyMatch(SnapshotConflictException.class::isInstance);
        }

        @Test
        @DisplayName("충돌한 스냅샷에 입력을 복사하지 않는다")
        void doesNotCopyInput() {
            assertThat(inputCount(snapshot(1))).isZero();
        }

        @Test
        @DisplayName("충돌한 manifest를 덮어쓰지 않는다")
        void doesNotOverwriteManifest() {
            assertThat(manifest(snapshot(1)).get("input_count")).isEqualTo(9);
        }

    }

    @Nested
    class InvalidQuantity {
        private JobExecution failed;
        private JobExecution restart;
        private List<String> stepsAfterFailure;
        private int inputAfterFailure;
        private Optional<String> publishedAfterFailure;

        @BeforeEach
        void fixInvalidQuantityThenRestart() throws Exception {
            database.insertActivity(42, 7, 0, Instant.parse("2026-08-17T01:00:00Z"));
            failed = launch(snapshot(1));
            stepsAfterFailure = executedSteps(failed);
            inputAfterFailure = inputCount(snapshot(1));
            publishedAfterFailure = publishedSnapshot(WEEK);
            jdbc.update("UPDATE product_purchase_activity SET quantity = 1 WHERE order_id = 42");
            restart = launch(snapshot(1));
        }
        @Test
        @DisplayName("잘못된 수량은 첫 실행을 실패시킨다")
        void firstFails() {
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("잘못된 수량은 입력 고정 단계에서 실패한다")
        void freezeFails() {
            assertThat(stepsAfterFailure).containsExactly(WeeklyRankingJobConfig.FREEZE_STEP);
        }

        @Test
        @DisplayName("잘못된 수량의 원인을 남긴다")
        void reportsInvalidInput() {
            assertThat(failed.getAllFailureExceptions()).anyMatch(InvalidPurchaseInputException.class::isInstance);
        }

        @Test
        @DisplayName("실패 후 반쪽 입력이 남지 않는다")
        void leavesNoPartialInput() {
            assertThat(inputAfterFailure).isZero();
        }

        @Test
        @DisplayName("실패 후 공개본이 생기지 않는다")
        void publishesNothing() {
            assertThat(publishedAfterFailure).isEmpty();
        }

        @Test
        @DisplayName("원본을 수정한 재시작은 같은 JobInstance다")
        void sameInstance() {
            assertThat(restart.getJobInstance().getInstanceId()).isEqualTo(failed.getJobInstance().getInstanceId());
        }

        @Test
        @DisplayName("원본을 수정하면 재시작이 완료된다")
        void restartCompletes() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("랭킹은 수정된 수량을 반영한다")
        void rankingUsesCorrectedQuantity() {
            assertThat(ranking(snapshot(1))).isEqualTo(List.of(List.of(7L, 1L, 1L)));
        }

    }

    @Nested
    class EmptyWeek {
        private JobExecution execution;

        @BeforeEach
        void runForWeekWithoutPurchases() throws Exception {
            execution = launch(snapshot(1));
        }
        @Test
        @DisplayName("빈 주도 정상 완료된다")
        void completes() {
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("빈 주의 스냅샷도 공개 상태다")
        void snapshotIsPublished() {
            assertThat(manifest(snapshot(1)).get("status")).isEqualTo(SnapshotStatus.PUBLISHED.name());
        }

        @Test
        @DisplayName("빈 주는 고정 입력이 없다")
        void inputIsEmpty() {
            assertThat(manifest(snapshot(1)).get("input_count")).isEqualTo(0);
        }

        @Test
        @DisplayName("빈 주는 랭킹 행이 없다")
        void rankingIsEmpty() {
            assertThat(ranking(snapshot(1))).isEmpty();
        }

        @Test
        @DisplayName("빈 스냅샷을 공개한다")
        void emptySnapshotIsPublic() {
            assertThat(publishedSnapshot(WEEK)).contains(snapshot(1));
        }

    }

}
