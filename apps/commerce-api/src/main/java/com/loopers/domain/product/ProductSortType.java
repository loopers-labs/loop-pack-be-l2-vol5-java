package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ProductSortType {
    LATEST("latest"),
    PRICE_ASC("price_asc"),
    LIKES_DESC("likes_desc");

    private final String value;

    /**
     * 정렬값이 없으면 최신순을 기본으로 한다.
     */
    public static ProductSortType from(String value) {
        if (value == null) {
            return LATEST;
        }
        return Arrays.stream(values())
            .filter(sortType -> sortType.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new CoreException(
                ErrorType.BAD_REQUEST, "[sort = " + value + "] 지원하지 않는 정렬 조건입니다."));
    }
}
