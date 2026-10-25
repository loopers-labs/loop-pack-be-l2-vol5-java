package com.loopers.domain.payment;

public record ProviderOrderId(String value) {

    private static final int MIN_LENGTH = 6;

    public ProviderOrderId {
        if (value == null || value.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("PG 주문 번호는 " + MIN_LENGTH + "자 이상이어야 합니다: " + value);
        }
    }

    public static ProviderOrderId of(long orderId) {
        return new ProviderOrderId("LP-ORD-%010d".formatted(orderId));
    }
}
