package com.loopers.domain.ranking;

import java.time.Instant;

public record RankingPurchase(long orderId, long productId, int quantity, Instant confirmedAt) {
    public RankingPurchase {
        if (orderId <= 0 || productId <= 0 || quantity <= 0 || confirmedAt == null) {
            throw new IllegalArgumentException("Ranking purchase requires positive identities, quantity and a confirmation instant");
        }
    }
}
