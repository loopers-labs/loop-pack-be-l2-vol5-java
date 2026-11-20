package com.loopers.domain.ranking;

public record RankedProduct(long productId, long score, long rank) {
    public RankedProduct {
        if (productId <= 0 || score <= 0 || rank <= 0) {
            throw new IllegalArgumentException("Ranking product, score and position must be positive");
        }
    }
}
