package com.loopers.interfaces.api;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int size, boolean hasNext) {

    public static <S, T> PageResponse<T> of(
        PageWindow<S> window, PageNumber page, PageSize size, Function<S, T> toItem) {
        return new PageResponse<>(
            window.items().stream().map(toItem).toList(), page.value(), size.value(), window.hasNext());
    }
}
