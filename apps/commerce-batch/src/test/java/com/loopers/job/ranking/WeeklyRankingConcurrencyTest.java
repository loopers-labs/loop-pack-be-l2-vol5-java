package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class WeeklyRankingConcurrencyTest extends WeeklyRankingJobTestSupport {
    @Test
    @DisplayName("같은 기간의 다른 실행이 진행 중이면 동시 실행 제한으로 실패한다")
    void overlappingExecutionFailsBecausePeriodIsRunning() throws Exception {
        database.insertWeek20260817Fixture();
        var firstStepStarted = new CountDownLatch(1);
        var finishFirstStep = new CountDownLatch(1);
        doAnswer(invocation -> {
            SnapshotId id = invocation.getArgument(0);
            if (id.value().equals(snapshot(1))) {
                firstStepStarted.countDown();
                if (!finishFirstStep.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for test to release the first job");
                }
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).copyActivities(any(SnapshotId.class));

        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> launch(snapshot(1)));
            if (!firstStepStarted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("first job did not reach the freeze step");
            }
            JobExecution second = launch(snapshot(2));
            finishFirstStep.countDown();
            first.get(20, TimeUnit.SECONDS);

            assertThat(second.getAllFailureExceptions())
                    .anySatisfy(error -> assertThat(error).hasMessageContaining("already running"));
        } finally {
            finishFirstStep.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 기간의 첫 작업이 실행 중이면 다른 revision은 시작하지 않는다")
    void preventsOverlappingExecutionsForSamePeriod() throws Exception {
        database.insertWeek20260817Fixture();
        var firstStepStarted = new CountDownLatch(1);
        var finishFirstStep = new CountDownLatch(1);
        doAnswer(invocation -> {
            SnapshotId id = invocation.getArgument(0);
            if (id.value().equals(snapshot(1))) {
                firstStepStarted.countDown();
                if (!finishFirstStep.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for test to release the first job");
                }
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).copyActivities(any(SnapshotId.class));

        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> launch(snapshot(1)));
            if (!firstStepStarted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("first job did not reach the freeze step");
            }
            JobExecution second = launch(snapshot(2));
            finishFirstStep.countDown();
            JobExecution completed = first.get(20, TimeUnit.SECONDS);

            assertThat(List.of(completed.getStatus(), second.getStatus()))
                    .containsExactly(BatchStatus.COMPLETED, BatchStatus.FAILED);
        } finally {
            finishFirstStep.countDown();
            executor.shutdownNow();
        }
    }
}
