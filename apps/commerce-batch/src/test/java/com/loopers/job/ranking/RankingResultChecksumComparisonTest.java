package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

class RankingResultChecksumComparisonTest extends WeeklyRankingJobTestSupport {
    @Test
    @DisplayName("같은 스냅샷 ID의 정상 실행과 실패 후 재시작은 같은 결과 체크섬을 만든다")
    void normalAndRestartedRunsHaveSameResultChecksum() throws Exception {
        String snapshotId = snapshot(1);

        database.insertWeek20260817Fixture();
        JobExecution normal = launch(snapshotId);
        if (normal.getStatus() != BatchStatus.COMPLETED) {
            throw new AssertionError("normal execution did not complete");
        }
        String normalChecksum = Objects.requireNonNull((String) manifest(snapshotId).get("result_checksum"));

        // 같은 스냅샷 ID를 재사용할 수 있도록 비교용 정상 실행만 초기화한다.
        database.reset();
        database.insertWeek20260817Fixture();
        var failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            long productId = invocation.getArgument(1);
            if (productId == 9L && failOnce.getAndSet(false)) {
                throw new IllegalStateException("injected failure before product 9");
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).productTotal(any(SnapshotId.class), anyLong());

        JobExecution failed = launch(snapshotId);
        if (failed.getStatus() != BatchStatus.FAILED || failOnce.get()) {
            throw new AssertionError("first execution did not fail at product 9");
        }
        JobExecution restart = launch(snapshotId);
        if (restart.getStatus() != BatchStatus.COMPLETED) {
            throw new AssertionError("restart did not complete");
        }

        assertThat(manifest(snapshotId).get("result_checksum")).isEqualTo(normalChecksum);
    }
}
