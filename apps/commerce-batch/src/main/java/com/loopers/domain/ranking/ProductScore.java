package com.loopers.domain.ranking;

public record ProductScore(String snapshotId, long productId, long score) {
    public ProductScore {
        if (snapshotId == null || snapshotId.isBlank() || snapshotId.length() > 64) {
            throw new IllegalArgumentException("A score requires its stored snapshot identity");
        }
        if (productId <= 0 || score <= 0) {
            throw new IllegalArgumentException("Product and absolute score must be positive");
        }
    }
}
