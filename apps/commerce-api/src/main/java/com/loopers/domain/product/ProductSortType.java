package com.loopers.domain.product;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;

import java.util.Arrays;

public enum ProductSortType {
    LATEST("latest"),
    PRICE_ASC("price_asc"),
    LIKES_DESC("likes_desc");

    private final String value;

    ProductSortType(String value) {
        this.value = value;
    }

    public static ProductSortType from(String value) {
        return Arrays.stream(values())
            .filter(type -> type.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new DomainException(DomainErrorType.INVALID_VALUE, "지원하지 않는 정렬입니다: " + value));
    }
}
