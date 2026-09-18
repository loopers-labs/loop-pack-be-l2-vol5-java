package com.loopers.support.page;

import java.util.List;
import java.util.function.Function;

/**
 * 한 페이지의 항목과 조건에 맞는 전체 항목 수다.
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(content.stream().map(mapper).toList(), page, size, totalElements);
    }
}
