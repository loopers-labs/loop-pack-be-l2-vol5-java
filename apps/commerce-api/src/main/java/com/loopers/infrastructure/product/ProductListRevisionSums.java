package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductSortType;

// 목록 revision 칸들을 정렬별로 더한 값
public interface ProductListRevisionSums {
    Long getLatest();

    Long getPrice();

    Long getLikes();

    default long of(ProductSortType sortType) {
        Long sum = switch (sortType) {
            case LATEST -> getLatest();
            case PRICE_ASC -> getPrice();
            case LIKES_DESC -> getLikes();
        };
        return sum == null ? 0L : sum;
    }
}
