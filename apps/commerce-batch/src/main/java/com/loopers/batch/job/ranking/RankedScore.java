package com.loopers.batch.job.ranking;

// 계산 순위 한 줄. calcRank는 삭제 여부와 무관하게 score DESC, product_id ASC로 매긴 순위다(D157).
public record RankedScore(long productId, long score, int calcRank) {
}
