package com.loopers.application.product.query;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public enum ProductSort {
    LATEST("latest"),
    PRICE_ASC("price_asc"),
    LIKES_DESC("likes_desc");

    private final String value;

    ProductSort(String value) {
        this.value = value;
    }

    public static ProductSort from(String value) {
        for (ProductSort sort : values()) {
            if (sort.value.equals(value)) {
                return sort;
            }
        }
        throw new CoreException(ErrorType.INVALID_REQUEST);
    }
}
