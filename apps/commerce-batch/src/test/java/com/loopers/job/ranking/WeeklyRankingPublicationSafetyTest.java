package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.domain.SnapshotConflictException;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

class WeeklyRankingPublicationSafetyTest extends WeeklyRankingJobTestSupport {
    private static final String INJECTED_PUBLICATION_FAILURE = "fail before publication";

    @MockitoSpyBean(name = WeeklyRankingJobConfig.CLOCK) private Clock rankingClock;

    @Test
    @DisplayName("주간 종료 1초 전에는 랭킹이 공개되지 않는다")
    void unfinishedWeekIsNotPublished() throws Exception {
        database.insertWeek20260817Fixture();
        doReturn(Instant.parse("2026-08-23T23:59:59Z")).when(rankingClock).instant();

        launch(snapshot(1));

        assertThat(publishedSnapshot(WEEK)).isEmpty();
    }

    @Test
    @DisplayName("주간 종료 전에 공개하면 기간이 끝나지 않았다는 이유로 실패한다")
    void unfinishedWeekFailsWithClearReason() throws Exception {
        database.insertWeek20260817Fixture();
        doReturn(Instant.parse("2026-08-23T23:59:59Z")).when(rankingClock).instant();

        JobExecution execution = launch(snapshot(1));

        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(error -> assertThat(error).hasMessageContaining("weekly ranking period has not ended"));
    }

    @Test
    @DisplayName("주간 종료 시각이 되면 랭킹을 공개할 수 있다")
    void weekCanBePublishedAtEnd() throws Exception {
        database.insertWeek20260817Fixture();
        doReturn(Instant.parse("2026-08-24T00:00:00Z")).when(rankingClock).instant();

        JobExecution execution = launch(snapshot(1));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("기간 종료 전 공개에 실패한 작업은 종료 후 같은 입력으로 재시작할 수 있다")
    void unfinishedWeekCanRestartAtEnd() throws Exception {
        database.insertWeek20260817Fixture();
        doReturn(Instant.parse("2026-08-23T23:59:59Z")).when(rankingClock).instant();
        launch(snapshot(1));
        doReturn(Instant.parse("2026-08-24T00:00:00Z")).when(rankingClock).instant();

        launch(snapshot(1));

        assertThat(publishedSnapshot(WEEK)).contains(snapshot(1));
    }

    @Test
    @DisplayName("공개 단계만 재시작할 때 고정 입력이 바뀌었다면 충돌로 실패한다")
    void publishRestartRejectsModifiedFrozenInput() throws Exception {
        database.insertActivity(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
        database.insertActivity(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));
        database.insertActivity(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));
        failNextPublication();
        JobExecution failed = launch(snapshot(1));
        requireInjectedPublicationFailure(failed);
        swapFrozenOrderQuantities(snapshot(1));

        JobExecution restart = launch(snapshot(1));

        assertThat(executedSteps(restart)).as("재시작 범위").containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(restart.getAllFailureExceptions()).anyMatch(SnapshotConflictException.class::isInstance);
    }

    @Test
    @DisplayName("공개 재시작 중 입력 변경이 발견되면 이전에 공개된 랭킹을 유지한다")
    void publishRestartWithModifiedInputKeepsPreviousPublication() throws Exception {
        database.insertWeek20260817Fixture();
        launch(snapshot(1));
        assertThat(publishedSnapshot(WEEK)).as("실패 전에 공개된 스냅샷").contains(snapshot(1));

        failNextPublication();
        JobExecution failed = launch(snapshot(2));
        requireInjectedPublicationFailure(failed);
        swapFrozenOrderQuantities(snapshot(2));

        JobExecution restart = launch(snapshot(2));
        assertThat(executedSteps(restart)).as("재시작 범위").containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(restart.getAllFailureExceptions()).as("공개 재시작이 실패한 이유")
                .anyMatch(SnapshotConflictException.class::isInstance);

        assertThat(publishedSnapshot(WEEK)).contains(snapshot(1));
    }

    private void failNextPublication() {
        var failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false)) {
                throw new IllegalStateException(INJECTED_PUBLICATION_FAILURE);
            }
            return invocation.callRealMethod();
        }).when(publicationStore).publishIfNewer(any(SnapshotId.class), any());
    }

    private void requireInjectedPublicationFailure(JobExecution failed) {
        assertThat(persistedStep(failed, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus())
                .as("첫 실행의 공개 Step 상태").isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getAllFailureExceptions()).as("첫 실행의 공개 실패 원인")
                .anyMatch(error -> INJECTED_PUBLICATION_FAILURE.equals(error.getMessage()));
    }

    private void swapFrozenOrderQuantities(String snapshotId) {
        // 상품 7의 두 주문 수량을 2·3에서 3·2로 바꾼다. 합계는 여전히 5다.
        int changed = jdbc.update("UPDATE ranking_snapshot_input SET quantity = CASE order_id WHEN 42 THEN 3 ELSE 2 END "
                + "WHERE snapshot_id = ? AND product_id = 7 AND order_id IN (42, 43)", snapshotId);
        assertThat(changed).as("수량이 바뀐 고정 입력 행 수").isEqualTo(2);
    }
}
