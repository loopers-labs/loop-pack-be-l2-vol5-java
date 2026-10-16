package com.loopers.domain.product;

/**
 * 재고 변경 결과. 품절 여부가 바뀌었는지를 함께 알린다.
 * 목록 캐시는 재고 수량이 아니라 품절 여부만 담으므로, 품절 여부가 바뀔 때만 무효화한다 (4주차 docs/week4/cache-design.md 3.3)
 */
public record StockChange(Product product, boolean soldOutChanged) {}
