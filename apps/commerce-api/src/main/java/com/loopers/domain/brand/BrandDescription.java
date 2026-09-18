package com.loopers.domain.brand;

public record BrandDescription(String value) {

    private static final int MAX_LENGTH = 200;

    public BrandDescription {
        if (value != null && value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "브랜드 설명은 " + MAX_LENGTH + "자를 넘을 수 없습니다: " + value.length());
        }
    }

    public static BrandDescription of(String value) {
        return new BrandDescription(value);
    }
}
