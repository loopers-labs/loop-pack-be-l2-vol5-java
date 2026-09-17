package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Locale;

/**
 * 상품 목록이 지원하는 정렬. 동률의 보조 정렬 기준은 구현에서 상품 ID 로 고정한다.
 */
public enum ProductSort {
    LATEST,
    PRICE_ASC,
    LIKES_DESC;

    public static ProductSort from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        try {
            return ProductSort.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.INVALID_SORT, "지원하지 않는 정렬입니다. [sort = " + value + "]");
        }
    }
}
