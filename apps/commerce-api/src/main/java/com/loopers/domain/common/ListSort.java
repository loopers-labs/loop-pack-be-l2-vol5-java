package com.loopers.domain.common;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Locale;

/**
 * 상품 목록을 제외한 목록이 지원하는 정렬.
 * latest 는 생성 시각 내림차순과 ID 내림차순, oldest 는 그 반대다.
 */
public enum ListSort {
    LATEST,
    OLDEST;

    public static ListSort from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        try {
            return ListSort.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.INVALID_SORT, "지원하지 않는 정렬입니다. [sort = " + value + "]");
        }
    }

    public boolean isDescending() {
        return this == LATEST;
    }
}
