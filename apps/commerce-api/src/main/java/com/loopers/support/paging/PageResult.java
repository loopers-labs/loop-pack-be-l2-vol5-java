package com.loopers.support.paging;

import java.util.List;
import java.util.function.Function;

/**
 * 페이지 조회 결과 (설계 4-3-0 Page&lt;T&gt;). totalCount 는 전체 항목 수.
 */
public record PageResult<T>(List<T> items, int page, int size, long totalCount) {
    public static <T> PageResult<T> of(List<T> items, PageQuery query, long totalCount) {
        return new PageResult<>(items, query.page(), query.size(), totalCount);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), page, size, totalCount);
    }
}
