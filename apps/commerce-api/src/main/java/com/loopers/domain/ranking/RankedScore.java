package com.loopers.domain.ranking;

/** 점수 행 1개. ranking 은 배치가 저장한 계산 순위 그대로이며, COMPLETED 회차라면 NULL 이 아니어야 한다(R4). */
public record RankedScore(long productId, long score, Integer ranking) {}
