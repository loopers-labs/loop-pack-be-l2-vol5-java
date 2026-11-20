package com.loopers.application.ranking.service;

import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import com.loopers.domain.ranking.repository.WeeklyPartialScoreRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WriteWeeklyRankingPartialScoreService {
    private final WeeklyPartialScoreRepository scores;

    public WriteWeeklyRankingPartialScoreService(WeeklyPartialScoreRepository scores) {
        this.scores = scores;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID snapshotId, int orderPartitionId, String ownerId, long generation, List<? extends WeeklyPurchaseScore> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        if (chunk.stream().anyMatch(score -> !snapshotId.equals(score.snapshotId()))) {
            throw new SnapshotConflictException("청크에 다른 snapshot의 상품 점수가 있습니다.");
        }
        scores.lockActiveOwner(snapshotId, orderPartitionId, ownerId, generation);
        var stored = scores.insertAndRead(snapshotId, orderPartitionId, chunk);
        for (var attempted : chunk) {
            long existing = stored.get(attempted.productId());
            if (existing != attempted.score()) {
                scores.recordConflict(orderPartitionId, ownerId, generation, attempted, existing);
                throw new SnapshotConflictException("같은 주문 파티션의 고정 상품 점수가 다릅니다: " + attempted.productId());
            }
        }
    }
}
