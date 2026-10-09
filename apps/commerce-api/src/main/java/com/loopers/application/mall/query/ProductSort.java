package com.loopers.application.mall.query;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.Arrays;

// 상품 목록 정렬 기준
public enum ProductSort {
    LATEST("latest"),
    PRICE_ASC("price_asc"),
    LIKES_DESC("likes_desc");

    private final String value;

    ProductSort(String value) {
        this.value = value;
    }

    // 문자열 값으로 정렬 기준 조회
    public static ProductSort from(String value) {
        return Arrays.stream(values())
            .filter(sort -> sort.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new DomainException(DomainErrorCode.INVALID_QUANTITY, "지원하지 않는 상품 정렬입니다."));
    }
}
