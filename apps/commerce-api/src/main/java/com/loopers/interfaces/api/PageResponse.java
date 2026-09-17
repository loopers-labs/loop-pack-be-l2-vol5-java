package com.loopers.interfaces.api;

import com.loopers.support.paging.PageResult;

import java.util.List;
import java.util.function.Function;

/** 설계 4-3-0 Page&lt;T&gt;. 페이지 정보는 meta 가 아니라 data 안 (DR-19). */
public record PageResponse<T>(List<T> items, int page, int size, long totalCount) {
    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(result.items().stream().map(mapper).toList(), result.page(), result.size(), result.totalCount());
    }
}
