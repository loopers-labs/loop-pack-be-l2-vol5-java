package com.loopers.batch.job.ranking;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

// Step 3 공개(docs/week9/commerce-ranking.md 7절). step TX 하나에서 완성 확인 → 최대 순위 다음부터 상위 K INSERT → pointer 조건부 갱신.
// 다시 돌려도 이미 있는 순위는 건드리지 않고 같은 snapshot이면 pointer도 그대로라 같은 결과로 수렴한다. 확장 job도 이 tasklet을 쓴다.
@Slf4j
public class PublishRankingTasklet implements Tasklet {
    public static final String RESULT_CHECKSUM_KEY = "resultChecksum";
    public static final String NEWER_PUBLISHED = "NEWER_PUBLISHED";

    private final WeeklyRankingRepository weeklyRankingRepository;
    private final String snapshotId;
    private final int rankingSize;

    public PublishRankingTasklet(WeeklyRankingRepository weeklyRankingRepository, String snapshotId, int rankingSize) {
        this.weeklyRankingRepository = weeklyRankingRepository;
        this.snapshotId = snapshotId;
        this.rankingSize = rankingSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SnapshotInfo info = weeklyRankingRepository.findInfo(snapshotId)
            .orElseThrow(() -> new IllegalStateException("고정되지 않은 snapshot은 공개하지 않는다. snapshotId=" + snapshotId));
        long expected = weeklyRankingRepository.distinctInputProducts(snapshotId);
        long scored = weeklyRankingRepository.stageCount(snapshotId);
        if (scored != expected) {
            throw new IllegalStateException("점수 계산이 끝나지 않은 snapshot은 공개하지 않는다. snapshotId=" + snapshotId
                + ", 입력 상품=" + expected + ", 점수 행=" + scored);
        }
        int filled = weeklyRankingRepository.maxCalcRank(snapshotId);
        List<RankedScore> added = filled < rankingSize
            ? weeklyRankingRepository.stageRanking(snapshotId, filled, rankingSize - filled) : List.of();
        weeklyRankingRepository.insertResults(snapshotId, added);
        contribution.incrementWriteCount(added.size());
        String checksum = ResultChecksum.of(weeklyRankingRepository.stageRanking(snapshotId, 0, Integer.MAX_VALUE));
        chunkContext.getStepContext().getStepExecution().getExecutionContext().putString(RESULT_CHECKSUM_KEY, checksum);
        weeklyRankingRepository.publish(info.periodStart(), snapshotId);
        Optional<String> published = weeklyRankingRepository.publishedSnapshotId(info.periodStart());
        if (published.isPresent() && !published.get().equals(snapshotId)) {
            log.info("더 새 공개본이 있어 pointer를 바꾸지 않았다. snapshotId={}, 공개본={}, 결과 checksum={}", snapshotId,
                published.get(), checksum);
            contribution.setExitStatus(new ExitStatus(NEWER_PUBLISHED, "공개본=" + published.get()));
        } else {
            log.info("주간 랭킹 공개. snapshotId={}, 추가한 순위={}, 결과 checksum={}", snapshotId, added.size(), checksum);
        }
        return RepeatStatus.FINISHED;
    }
}
