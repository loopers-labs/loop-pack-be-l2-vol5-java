package com.loopers.domain.product;

import org.springframework.data.domain.Sort;

public enum ProductSortType {
    LATEST(Sort.by(Sort.Direction.DESC, "createdAt")),
    PRICE_ASC(Sort.by(Sort.Direction.ASC, "price")),
    // TODO: 좋아요 기능 추가 후 좋아요 수 기준 정렬로 교체 (현재는 LATEST로 대체)
    LIKES_DESC(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final Sort sort;

    ProductSortType(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
