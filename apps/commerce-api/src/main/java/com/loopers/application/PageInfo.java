package com.loopers.application;

import java.util.List;

public record PageInfo<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageInfo<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageInfo<>(content, page, size, totalElements, totalPages);
    }
}
