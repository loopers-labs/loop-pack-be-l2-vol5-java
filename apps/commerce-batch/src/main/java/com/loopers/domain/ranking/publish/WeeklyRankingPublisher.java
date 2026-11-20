package com.loopers.domain.ranking.publish;

import com.loopers.domain.ranking.PublishOutcome;
import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.SnapshotChecksum;
import com.loopers.domain.ranking.WeeklyPeriod;
import com.loopers.domain.ranking.WeeklyRankingPublication;
import com.loopers.domain.ranking.WeeklyRankingPublicationRepository;
import com.loopers.domain.ranking.WeeklyRankingSnapshot;
import com.loopers.domain.ranking.WeeklyRankingSnapshotRepository;
import com.loopers.domain.ranking.score.ProductScore;
import com.loopers.domain.ranking.score.WeeklyRankingScoreStage;
import com.loopers.domain.ranking.score.WeeklyRankingScoreStageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 계산이 끝난 snapshot 을 검증·순위 매김·공개한다. 호출하는 쪽의 TX 하나에서 완성 ranking·snapshot 상태·pointer 가
 * 함께 commit 되거나 함께 rollback 된다. 기존 공개본을 먼저 지우는 단계는 없다.
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingPublisher {

    private final WeeklyRankingPublicationRepository publicationRepository;
    private final WeeklyRankingSnapshotRepository snapshotRepository;
    private final WeeklyRankingScoreStageRepository stageRepository;
    private final WeeklyRankingRepository rankingRepository;
    private final Clock clock;

    public PublishOutcome publish(WeeklyPeriod period, int revision) {
        WeeklyRankingPublication publication = publicationRepository.findForUpdate(period.periodStart())
            .orElseThrow(() -> new IllegalStateException(period.periodStart() + " 의 입력이 고정되지 않았습니다."));
        String snapshotId = period.snapshotId(revision);
        WeeklyRankingSnapshot snapshot = snapshotRepository.findBySnapshotId(snapshotId)
            .orElseThrow(() -> new IllegalStateException(snapshotId + " 가 없습니다."));

        // 공개 commit 뒤 Batch metadata 저장만 실패했던 재실행은 같은 결과로 수렴한다.
        switch (snapshot.getStatus()) {
            case PUBLISHED -> {
                return PublishOutcome.ALREADY_PUBLISHED;
            }
            case SUPERSEDED -> {
                return PublishOutcome.SUPERSEDED;
            }
            case FROZEN -> { }
        }

        List<ProductScore> scores = stageRepository.findAll(snapshotId).stream()
            .map(WeeklyRankingScoreStage::toScore)
            .toList();
        snapshot.verifyScoredProducts(scores.size());
        List<RankedProduct> ranked = RankingOrder.rank(scores);

        PublishOutcome outcome = publication.publish(snapshot, SnapshotChecksum.ofResult(ranked), ZonedDateTime.now(clock));
        if (outcome == PublishOutcome.PUBLISHED) {
            rankingRepository.saveAll(ranked.stream().map(product -> new WeeklyRanking(snapshotId, product)).toList());
        }
        return outcome;
    }
}
