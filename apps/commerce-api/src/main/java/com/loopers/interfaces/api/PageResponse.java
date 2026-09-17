package com.loopers.interfaces.api;

import com.loopers.application.PageInfo;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <I, T> PageResponse<T> from(PageInfo<I> info, Function<I, T> mapper) {
        return new PageResponse<>(info.content().stream().map(mapper).toList(), info.page(), info.size(), info.totalElements(), info.totalPages());
    }
}
