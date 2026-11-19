package com.loopers.domain.ranking.policy;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import java.time.Instant;

public final class FrozenPurchaseInputPolicy {
    private FrozenPurchaseInputPolicy() {}

    public record Row(long orderId, long productId, int quantity, Instant confirmedAt) {}

    /** previous is the previously accepted row; null starts an independent sequence. */
    public static void requireValid(RankingPeriod period, Row current, Row previous) {
        if (period == null || current == null || current.orderId() <= 0 || current.productId() <= 0
            || current.quantity() <= 0 || current.confirmedAt() == null || !period.contains(current.confirmedAt())
            || current.confirmedAt().getNano() % 1000 != 0) {
            throw new SnapshotIntegrityException("고정 입력의 식별자·수량·UTC 기간·마이크로초 계약을 위반했습니다.");
        }
        if (previous != null && (current.orderId() < previous.orderId()
            || (current.orderId() == previous.orderId() && current.productId() <= previous.productId()))) {
            throw new SnapshotIntegrityException("고정 입력은 중복 없이 orderId·productId 오름차순이어야 합니다.");
        }
    }
}
