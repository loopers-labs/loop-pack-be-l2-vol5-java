package com.loopers.batch.job.weeklyranking.step;

import com.loopers.domain.ranking.RankingInputException;
import com.loopers.domain.ranking.RankingInputException.Reason;
import com.loopers.domain.ranking.RankingInputManifest;
import com.loopers.domain.ranking.RankingProductScore;
import com.loopers.domain.ranking.RankingScoreRepository;
import com.loopers.domain.ranking.SnapshotStatus;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemProcessor;

/** 같은 고정 입력에서 상품 하나의 quantity 합을 계산한다. */
@RequiredArgsConstructor
public class WeeklyRankingScoreProcessor implements ItemProcessor<Long, RankingProductScore>, StepExecutionListener {

    private final WeeklyRankingRequest request;
    private final RankingScoreRepository repository;

    /** 계산 전 저장된 manifest 가 요청과 맞고 READY 인지 확인한다. */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        RankingInputManifest manifest = repository.findSnapshot(request.snapshotId())
            .orElseThrow(() -> new RankingInputException(Reason.INCONSISTENT_INPUT, "고정 입력 snapshot 이 없습니다."));
        if (!manifest.matches(request)) {
            throw new RankingInputException(Reason.SNAPSHOT_CONTRACT_MISMATCH, "snapshot 이 요청과 다릅니다.");
        }
        if (manifest.status() != SnapshotStatus.READY || manifest.deleted() || manifest.inputChecksum() == null) {
            throw new RankingInputException(Reason.INCONSISTENT_INPUT, "계산할 수 있는 READY snapshot 이 아닙니다.");
        }
    }

    @Override
    public RankingProductScore process(Long productId) {
        return repository.sumQuantity(request.snapshotId(), productId)
            .map(score -> new RankingProductScore(productId, score))
            .orElseThrow(() -> new RankingInputException(Reason.INCONSISTENT_INPUT, "상품 입력 합계가 없습니다: " + productId));
    }
}
