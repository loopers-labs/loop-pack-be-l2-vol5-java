package com.loopers.application.product;

import com.loopers.domain.product.ProductSortType;

// brandId가 null이면 브랜드를 지정하지 않은 전체 목록이다. revision이 바뀌면 다른 키가 된다
public record ProductListCacheKey(Long brandId, long revision, ProductSortType sortType, int page, int size) {
}
