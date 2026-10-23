package com.loopers.application.product.dto;

import com.loopers.domain.product.ProductSort;

/**
 * 상품 목록 캐시를 찾고 저장하는 조회 조건. 조건이 하나라도 다르면 다른 캐시 항목이다.
 *
 * @param brandId 브랜드 필터. {@code null} 이면 전체 목록이다.
 */
public record ProductListQuery(Long brandId, ProductSort sort, int page, int size) {
}
