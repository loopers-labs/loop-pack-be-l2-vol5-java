package com.loopers.domain.ranking.model.vo;

public record RankingResultFingerprint(long count, String sha256) {
    public static final int CONTRACT_VERSION = 1;
}
