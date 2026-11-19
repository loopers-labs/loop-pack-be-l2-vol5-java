package com.loopers.domain.ranking.model.vo;

public record InputFingerprint(long count, String sha256) {
    public static final int CONTRACT_VERSION = 1;
    public static final int PARTITIONED_CONTRACT_VERSION = 3;
}
