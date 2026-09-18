package com.loopers.domain.brand;

public record BrandName(String value) {

    private static final int MAX_LENGTH = 50;

    public BrandName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("브랜드 이름은 비어 있을 수 없습니다");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "브랜드 이름은 " + MAX_LENGTH + "자를 넘을 수 없습니다: " + value.length());
        }
    }

    public static BrandName of(String value) {
        return new BrandName(value);
    }
}
