package com.loopers.interfaces.api.product;

import com.loopers.domain.product.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Arrays;

/** 외부 계약의 정렬 값을 도메인 정렬로 바꾼다. 모르는 값은 기본값으로 바꾸지 않고 거절한다 (설계 6.1). */
public enum ProductSortParam {
    LATEST("latest", ProductSort.LATEST),
    PRICE_ASC("price_asc", ProductSort.PRICE_ASC),
    LIKES_DESC("likes_desc", ProductSort.LIKES_DESC);

    private final String value;
    private final ProductSort sort;

    ProductSortParam(String value, ProductSort sort) {
        this.value = value;
        this.sort = sort;
    }

    public static ProductSort toSort(String value) {
        return Arrays.stream(values())
            .filter(param -> param.value.equals(value))
            .findFirst()
            .map(param -> param.sort)
            .orElseThrow(() -> new CoreException(ErrorType.BAD_REQUEST,
                "sort 는 latest, price_asc, likes_desc 중 하나여야 합니다."));
    }
}
