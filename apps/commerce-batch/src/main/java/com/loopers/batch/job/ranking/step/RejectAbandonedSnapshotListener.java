package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository;
import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository.SnapshotManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

/**
 * 고정 입력이 없거나 포기한(ABANDONED) snapshot 이면 step 을 시작하지 않는다.
 * 포기한 revision 으로 job 을 직접 실행해도 이어지지 않게 한다 (9주차 D-185)
 */
@RequiredArgsConstructor
@Component
public class RejectAbandonedSnapshotListener implements StepExecutionListener {

    private final WeeklyRankingSnapshotRepository snapshots;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        String snapshotId = WeeklyRankingParameters.from(stepExecution.getJobParameters()).snapshotId();
        SnapshotManifest manifest = snapshots.find(snapshotId)
            .orElseThrow(() -> new IllegalStateException("고정된 입력이 없음. snapshotId=" + snapshotId));
        if (manifest.is("ABANDONED")) {
            throw new IllegalStateException("포기한 snapshot 은 이어 가지 않음. snapshotId=" + snapshotId);
        }
    }
}
