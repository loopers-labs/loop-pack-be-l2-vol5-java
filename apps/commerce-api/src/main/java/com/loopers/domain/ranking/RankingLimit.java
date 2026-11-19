package com.loopers.domain.ranking;

public record RankingLimit(int value) {

    public static final int MAX = 100;
    public static final RankingLimit DEFAULT = new RankingLimit(10);

    public RankingLimit {
        if (value < 1 || value > MAX) {
            throw new IllegalArgumentException("limit 은 1 이상 " + MAX + " 이하여야 합니다: " + value);
        }
    }

    public static RankingLimit of(int value) {
        return new RankingLimit(value);
    }
}
