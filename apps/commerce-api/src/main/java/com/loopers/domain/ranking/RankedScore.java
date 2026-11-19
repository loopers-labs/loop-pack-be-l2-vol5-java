package com.loopers.domain.ranking;

/** 완성본의 상품 1행. ranking 은 배치가 저장한 계산 순위 그대로다. */
public record RankedScore(long productId, long score, int ranking) {}
