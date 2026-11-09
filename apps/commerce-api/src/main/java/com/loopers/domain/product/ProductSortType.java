package com.loopers.domain.product;

import org.springframework.data.domain.Sort;

// 정렬 값이 같은 상품은 id로 순서를 고정한다 — 동점 상품이 페이지 사이에서 중복·누락되지 않게
public enum ProductSortType {
    LATEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    PRICE_ASC(Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"))),
    // 좋아요 수는 상품의 컬럼이 아니라서, repository의 좋아요순 조회가 (좋아요 수 내림차순, id 오름차순)으로 정렬한다
    LIKES_DESC(Sort.unsorted());

    private final Sort sort;

    ProductSortType(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
