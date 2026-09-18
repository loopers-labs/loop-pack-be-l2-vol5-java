package com.loopers.domain.product;

/** 고객 상품 목록의 정렬. 값이 같으면 모두 식별자 역순으로 가른다 (설계 6.1). */
public enum ProductSort {
    LATEST,
    PRICE_ASC,
    LIKES_DESC
}
