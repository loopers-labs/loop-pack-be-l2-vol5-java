package com.loopers.domain.ranking.score;

import com.loopers.domain.ranking.freeze.WeeklyRankingSnapshotInputRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 고정한 snapshot 입력만으로 상품 점수를 계산하고 stage 에 절대값으로 쓴다. */
@RequiredArgsConstructor
@Component
public class WeeklyScoreCalculator {

    private final WeeklyRankingSnapshotInputRepository inputRepository;
    private final WeeklyRankingScoreStageRepository stageRepository;

    public ProductScore calculate(String snapshotId, Long productId) {
        return new ProductScore(productId, inputRepository.sumQuantity(snapshotId, productId));
    }

    /** 같은 상품을 다시 처리해도 score += 가 아니라 score = 로 같은 값에 수렴한다. */
    public void record(String snapshotId, ProductScore score) {
        stageRepository.find(snapshotId, score.productId())
            .ifPresentOrElse(
                stage -> stage.overwrite(score),
                () -> stageRepository.save(new WeeklyRankingScoreStage(snapshotId, score)));
    }
}
