package com.loopers.batch.job.ranking;

public record ProductScore(String snapshotId, long productId, long score) {
}
