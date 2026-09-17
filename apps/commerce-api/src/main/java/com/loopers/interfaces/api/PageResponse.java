package com.loopers.interfaces.api;

import com.loopers.domain.common.PageResult;

import java.util.List;
import java.util.function.Function;

/**
 * 모든 목록 API 가 공통으로 사용하는 페이지 응답.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> of(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(
            result.items().stream().map(mapper).toList(),
            result.page(),
            result.size(),
            result.totalElements(),
            result.totalPages()
        );
    }
}
