package com.loopers.product.application.port.out;

import com.loopers.product.domain.ProductSort;
import org.springframework.data.domain.Pageable;

/**
 * 고객 상품 목록 캐시의 조회 조건. 응답을 가르는 조건(브랜드·정렬·페이지·크기)이 모두 들어가야 다른 조건의 목록이 섞이지 않는다.
 * 사용자별 정보는 목록 응답에 없으므로 키에도 없다.
 */
public record ProductListKey(Long brandId, ProductSort sort, int page, int size) {

    public static ProductListKey of(Long brandId, ProductSort sort, Pageable pageable) {
        return new ProductListKey(brandId, sort, pageable.getPageNumber(), pageable.getPageSize());
    }
}
