package com.loopers.interfaces.api;

import com.loopers.support.page.PageResult;

import java.util.List;
import java.util.function.Function;

public record ListResponse<T>(List<T> content, int page, int size, long totalElements) {

    public static <S, T> ListResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        PageResult<T> mapped = result.map(mapper);
        return new ListResponse<>(mapped.content(), mapped.page(), mapped.size(), mapped.totalElements());
    }
}
