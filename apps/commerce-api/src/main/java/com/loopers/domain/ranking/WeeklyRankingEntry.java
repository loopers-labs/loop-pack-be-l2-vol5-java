package com.loopers.domain.ranking;

/** 완성 ranking 한 줄. rank는 계산 당시 순위라, 그 뒤 삭제된 상품을 빼면 1, 3처럼 빌 수 있다. */
public record WeeklyRankingEntry(int rank, long productId, long score) {
}
