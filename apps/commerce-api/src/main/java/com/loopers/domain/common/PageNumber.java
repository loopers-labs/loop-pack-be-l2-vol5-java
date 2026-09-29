package com.loopers.domain.common;

public record PageNumber(int value) {

    public static final int MAX = 500;
    public static final PageNumber FIRST = new PageNumber(0);

    public PageNumber {
        if (value < 0 || value > MAX) {
            throw new IllegalArgumentException("page 는 0 이상 " + MAX + " 이하여야 합니다: " + value);
        }
    }

    public static PageNumber of(int value) {
        return new PageNumber(value);
    }

    public int offsetWith(PageSize size) {
        return value * size.value();
    }
}
