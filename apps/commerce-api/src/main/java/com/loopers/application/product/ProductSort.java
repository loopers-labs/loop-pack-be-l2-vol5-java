package com.loopers.application.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public enum ProductSort {
    LATEST,
    PRICE_ASC,
    LIKES_DESC,
    POPULAR;

    public static ProductSort from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        try {
            return ProductSort.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new CoreException(ErrorType.BAD_REQUEST, "지원하지 않는 상품 정렬입니다.");
        }
    }
}
