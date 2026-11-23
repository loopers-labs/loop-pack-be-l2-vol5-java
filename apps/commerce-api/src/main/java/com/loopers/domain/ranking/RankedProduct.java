package com.loopers.domain.ranking;

/**
 * 완성 순위 한 줄과 현재 상품 표시 정보. rank는 저장된 계산 순위다.
 */
public record RankedProduct(
    int rank,
    long score,
    long productId,
    String productName,
    long price,
    long brandId,
    String brandName
) {
}
