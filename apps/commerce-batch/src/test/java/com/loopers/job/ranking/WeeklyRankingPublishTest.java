package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.domain.SnapshotStatus;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

class WeeklyRankingPublishTest extends WeeklyRankingJobTestSupport {

    @DisplayName("pointer를 바꾼 직후 실패하면 이전 공개본이 유지된다")
    @Test
    void pointerUpdateRollsBackWhenPublicationFails() throws Exception {
        database.insertWeek20260817Fixture();
        launch(snapshot(1));
        var pointerWasUpdated = new AtomicBoolean(false);
        doAnswer(invocation -> {
            pointerWasUpdated.set((boolean) invocation.callRealMethod());
            throw new IllegalStateException("injected failure after pointer update");
        }).when(publicationStore).publishIfNewer(any(SnapshotId.class), any());

        launch(snapshot(2));

        if (!pointerWasUpdated.get()) {
            throw new AssertionError("test did not reach the pointer update");
        }
        assertThat(publishedSnapshot(WEEK)).contains(snapshot(1));
    }

    @DisplayName("pointer를 바꾼 직후 실패하면 새 순위 결과가 남지 않는다")
    @Test
    void rankingRowsRollBackWhenPublicationFails() throws Exception {
        database.insertWeek20260817Fixture();
        launch(snapshot(1));
        var pointerWasUpdated = new AtomicBoolean(false);
        doAnswer(invocation -> {
            pointerWasUpdated.set((boolean) invocation.callRealMethod());
            throw new IllegalStateException("injected failure after pointer update");
        }).when(publicationStore).publishIfNewer(any(SnapshotId.class), any());

        launch(snapshot(2));

        if (!pointerWasUpdated.get()) {
            throw new AssertionError("test did not reach the pointer update");
        }
        assertThat(ranking(snapshot(2))).isEmpty();
    }

    @Nested
    class PublicationFailsBeforePointerUpdate {
        private JobExecution normal;
        private JobExecution failed;
        private JobExecution restart;
        private Optional<String> publicationAfterFailure;
        private List<List<Long>> rankingAfterFailure;
        private Object statusAfterFailure;

        @BeforeEach
        void failBeforePointerUpdateThenRestart() throws Exception {
            database.insertWeek20260817Fixture();
            normal = launch(snapshot(1));
            var failOnce = new AtomicBoolean(true);
            doAnswer(invocation -> {
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("injected failure before pointer change");
                }
                return invocation.callRealMethod();
            }).when(publicationStore).publishIfNewer(any(SnapshotId.class), any());

            failed = launch(snapshot(2));
            publicationAfterFailure = publishedSnapshot(WEEK);
            rankingAfterFailure = ranking(snapshot(2));
            statusAfterFailure = manifest(snapshot(2)).get("status");
            restart = launch(snapshot(2));
        }
        @Test
        @DisplayName("기존 공개본은 정상 실행으로 만들어진다")
        void normalCompletes() {
            assertThat(normal.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("새 공개 단계가 실패한다")
        void firstFails() {
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("공개 실패 후 이전 공개본을 유지한다")
        void oldPublicationRemains() {
            assertThat(publicationAfterFailure).contains(snapshot(1));
        }

        @Test
        @DisplayName("공개 실패 후 새 랭킹 행이 남지 않는다")
        void noNewRankingAfterFailure() {
            assertThat(rankingAfterFailure).isEmpty();
        }

        @Test
        @DisplayName("공개 실패 후 스냅샷은 FROZEN 상태다")
        void snapshotStaysFrozen() {
            assertThat(statusAfterFailure).isEqualTo(SnapshotStatus.FROZEN.name());
        }

        @Test
        @DisplayName("공개 단계를 재시작하면 완료된다")
        void restartCompletes() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("재시작은 공개 단계만 실행한다")
        void restartOnlyPublishes() {
            assertThat(executedSteps(restart)).containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        }

        @Test
        @DisplayName("재시작 성공 후 새 스냅샷을 공개한다")
        void restartChangesPointer() {
            assertThat(publishedSnapshot(WEEK)).contains(snapshot(2));
        }

        @Test
        @DisplayName("재시작 성공 후 상품 세 개의 랭킹을 저장한다")
        void restartSavesAllRankingRows() {
            assertThat(ranking(snapshot(2))).hasSize(3);
        }

    }

    @Nested
    class StagedScoresDifferFromFrozenInput {
        private JobExecution execution;

        @BeforeEach
        void changeAStagedScoreBeforePublication() throws Exception {
            database.insertWeek20260817Fixture();
            doAnswer(invocation -> {
                invocation.callRealMethod();
                jdbc.update("UPDATE ranking_score_stage SET score = 50 WHERE snapshot_id = ? AND product_id = 9",
                        snapshot(1));
                return null;
            }).when(scoreStageStore).upsert(any(SnapshotId.class), anyList());
            execution = launch(snapshot(1));
        }
        @Test
        @DisplayName("고정 입력과 stage 점수가 다르면 Job이 실패한다")
        void jobFails() {
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("점수가 다르면 공개 Step이 실패한다")
        void publishFails() {
            assertThat(persistedStep(execution, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("점수가 다르면 공개 pointer가 생기지 않는다")
        void nothingIsPublished() {
            assertThat(publishedSnapshot(WEEK)).isEmpty();
        }

        @Test
        @DisplayName("점수가 다르면 완성 랭킹이 저장되지 않는다")
        void noRankingIsStored() {
            assertThat(ranking(snapshot(1))).isEmpty();
        }

        @Test
        @DisplayName("점수가 다르면 스냅샷은 FROZEN으로 남는다")
        void snapshotRemainsFrozen() {
            assertThat(manifest(snapshot(1)).get("status")).isEqualTo(SnapshotStatus.FROZEN.name());
        }

    }

    @Nested
    class OlderRevisionFinishesAfterNewerRevision {
        private JobExecution newer;
        private JobExecution stale;

        @BeforeEach
        void publishNewerThenRunOlderRevision() throws Exception {
            database.insertWeek20260817Fixture();
            newer = launch(snapshot(10));
            stale = launch(snapshot(9));
        }
        @Test
        @DisplayName("새 revision이 먼저 정상 완료된다")
        void newerCompletes() {
            assertThat(newer.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("오래된 revision도 정상 종료된다")
        void olderCompletes() {
            assertThat(stale.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("오래된 revision은 SKIPPED_STALE을 기록한다")
        void olderReportsSkipped() {
            assertThat(persistedStep(stale, WeeklyRankingJobConfig.PUBLISH_STEP).getExitStatus().getExitDescription()).contains("SKIPPED_STALE");
        }

        @Test
        @DisplayName("오래된 실행은 공개 pointer를 되돌리지 않는다")
        void newerRemainsPublished() {
            assertThat(publishedSnapshot(WEEK)).contains(snapshot(10));
        }

        @Test
        @DisplayName("오래된 스냅샷은 SKIPPED_STALE 상태다")
        void olderSnapshotIsMarkedSkipped() {
            assertThat(manifest(snapshot(9)).get("status")).isEqualTo(SnapshotStatus.SKIPPED_STALE.name());
        }

        @Test
        @DisplayName("오래된 실행의 랭킹 세 행은 보관된다")
        void olderRankingIsRetained() {
            assertThat(ranking(snapshot(9))).hasSize(3);
        }

    }

    @Nested
    class PublicationCommittedButBatchCompletionWasLost {
        private JobExecution completed;
        private JobExecution restart;
        private Object publishedAtBeforeRestart;
        private Object resultChecksumBeforeRestart;

        @BeforeEach
        void loseBatchCompletionAfterPublicationThenRestart() throws Exception {
            database.insertWeek20260817Fixture();
            completed = launch(snapshot(1));
            resultChecksumBeforeRestart = manifest(snapshot(1)).get("result_checksum");
            publishedAtBeforeRestart = jdbc.queryForObject(
                    "SELECT published_at FROM ranking_publication WHERE period_start = ?", Object.class, WEEK);
            markPublishStepFailed(completed);
            restart = launch(snapshot(1));
        }
        @Test
        @DisplayName("공개 재시작은 같은 JobInstance다")
        void sameInstance() {
            assertThat(restart.getJobInstance().getInstanceId()).isEqualTo(completed.getJobInstance().getInstanceId());
        }

        @Test
        @DisplayName("공개 재시작은 정상 완료된다")
        void restartCompletes() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("완료 기록을 잃으면 공개 단계만 재실행한다")
        void onlyPublicationRestarts() {
            assertThat(executedSteps(restart)).containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        }

        @Test
        @DisplayName("재공개 후에도 랭킹은 세 행이다")
        void rankingDoesNotDuplicate() {
            assertThat(ranking(snapshot(1))).hasSize(3);
        }

        @Test
        @DisplayName("재공개 후에도 기존 스냅샷이 공개된다")
        void sameSnapshotRemainsPublished() {
            assertThat(publishedSnapshot(WEEK)).contains(snapshot(1));
        }

        @Test
        @DisplayName("이미 공개한 시각을 재시작에서 바꾸지 않는다")
        void publicationTimeDoesNotChange() {
            assertThat(jdbc.queryForObject("SELECT published_at FROM ranking_publication WHERE period_start = ?", Object.class, WEEK)).isEqualTo(publishedAtBeforeRestart);
        }

        @Test
        @DisplayName("같은 스냅샷의 공개 단계를 재시작해도 실제 랭킹의 체크섬은 유지된다")
        void sameSnapshotKeepsResultChecksumAfterRestart() {
            var id = WeeklyRankingParameters.of(WEEK, snapshot(1)).snapshotId();
            assertThat(completed.getStatus()).as("재시작 전 공개 실행 상태").isEqualTo(BatchStatus.COMPLETED);
            assertThat(restart.getStatus()).as("재시작 실행 상태").isEqualTo(BatchStatus.COMPLETED);
            assertThat(persistedStep(restart, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus())
                    .as("재시작한 공개 Step 상태").isEqualTo(BatchStatus.COMPLETED);

            assertThat(publicationStore.resultChecksum(id)).isEqualTo(resultChecksumBeforeRestart);
        }

    }

    private void markPublishStepFailed(JobExecution completed) {
        JobExecution stored = jobExplorer.getJobExecution(completed.getId());
        var publish = stored.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(WeeklyRankingJobConfig.PUBLISH_STEP))
                .findFirst().orElseThrow();
        publish.setStatus(BatchStatus.FAILED);
        publish.setExitStatus(ExitStatus.FAILED);
        jobRepository.update(publish);
        stored.setStatus(BatchStatus.FAILED);
        stored.setExitStatus(ExitStatus.FAILED);
        jobRepository.update(stored);
    }

}
