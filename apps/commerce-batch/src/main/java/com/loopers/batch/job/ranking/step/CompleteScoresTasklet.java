package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository;
import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository.SnapshotManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 입력의 모든 상품에 점수가 있는지 확인하고 FROZEN → COMPLETED 로 바꾼다. 이미 COMPLETED 면 그대로 끝낸다 */
@RequiredArgsConstructor
@Component
public class CompleteScoresTasklet implements Tasklet {

    private final WeeklyRankingSnapshotRepository snapshots;
    private final Clock clock;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String snapshotId = WeeklyRankingParameters.from(contribution.getStepExecution().getJobParameters()).snapshotId();
        SnapshotManifest manifest = snapshots.find(snapshotId)
            .orElseThrow(() -> new IllegalStateException("고정된 입력이 없음. snapshotId=" + snapshotId));
        if (manifest.is("COMPLETED")) {
            return RepeatStatus.FINISHED;
        }

        int scores = snapshots.countScores(snapshotId);
        int products = snapshots.countInputProducts(snapshotId);
        if (scores != products) {
            throw new IllegalStateException("점수가 빠진 상품이 있음. snapshotId=" + snapshotId + " scores=" + scores + " products=" + products);
        }
        if (snapshots.markCompleted(snapshotId, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)) != 1) {
            throw new IllegalStateException("FROZEN 이 아니라 계산 완료로 바꿀 수 없음. manifest=" + manifest);
        }
        return RepeatStatus.FINISHED;
    }
}
