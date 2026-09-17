package com.loopers.domain.catalog;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * FR-PRODUCT-01 정렬 (ASM-08). 정확히 하나, 기본 latest. 동률 보조 기준은 상품 id 내림차순.
 * 허용 목록 밖·둘 이상 지정은 ER-07 INVALID_SORT.
 */
public enum ProductSort {
    LATEST("latest"),
    PRICE_ASC("price_asc"),
    LIKES_DESC("likes_desc");

    private final String value;

    ProductSort(String value) {
        this.value = value;
    }

    public static ProductSort from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        for (ProductSort sort : values()) {
            if (sort.value.equals(value)) {
                return sort;
            }
        }
        throw new CoreException(ErrorType.INVALID_SORT, "정렬 값이 올바르지 않습니다. [sort = " + value + "]");
    }
}
