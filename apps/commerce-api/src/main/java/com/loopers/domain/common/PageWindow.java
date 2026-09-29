package com.loopers.domain.common;

import java.util.List;

public record PageWindow<T>(List<T> items, boolean hasNext) {

    public static int limitOf(PageSize size) {
        return size.value() + 1;
    }

    public static <T> PageWindow<T> of(List<T> rows, PageSize size) {
        boolean hasNext = rows.size() > size.value();
        return new PageWindow<>(hasNext ? List.copyOf(rows.subList(0, size.value())) : List.copyOf(rows), hasNext);
    }
}
