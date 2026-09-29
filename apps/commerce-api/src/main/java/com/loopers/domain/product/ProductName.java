package com.loopers.domain.product;

public record ProductName(String value) {

    private static final int MAX_LENGTH = 100;

    public ProductName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("상품 이름은 비어 있을 수 없습니다");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "상품 이름은 " + MAX_LENGTH + "자를 넘을 수 없습니다: " + value.length());
        }
    }

    public static ProductName of(String value) {
        return new ProductName(value);
    }
}
