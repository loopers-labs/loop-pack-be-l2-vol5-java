package com.loopers.domain.ranking;

import com.loopers.domain.product.Product;

/** 공개 조건을 통과한 랭킹 항목과 그 상품. */
public record RankedProduct(WeeklyRanking ranking, Product product) {
}
