package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public enum ProductSortType {
    LATEST("latest"),
    PRICE_ASC("price_asc"),
    LIKES_DESC("likes_desc");

    private final String value;

    ProductSortType(String value) {
        this.value = value;
    }

    public static ProductSortType from(String value) {
        for (ProductSortType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new CoreException(ErrorType.BAD_REQUEST,
            "지원하지 않는 정렬 값입니다. (입력: " + value + ", 지원: latest, price_asc, likes_desc)");
    }
}
