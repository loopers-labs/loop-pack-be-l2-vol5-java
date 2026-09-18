package com.loopers.domain.common;

public record PageSize(int value) {

    public static final int MAX = 100;
    public static final PageSize DEFAULT = new PageSize(20);

    public PageSize {
        if (value < 1 || value > MAX) {
            throw new IllegalArgumentException("size 는 1 이상 " + MAX + " 이하여야 합니다: " + value);
        }
    }

    public static PageSize of(int value) {
        return new PageSize(value);
    }
}
