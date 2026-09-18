package com.loopers.interfaces.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int size, long totalCount, int totalPages) {
    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
            page.getContent().stream().map(mapper).toList(),
            page.getNumber() + 1,
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
