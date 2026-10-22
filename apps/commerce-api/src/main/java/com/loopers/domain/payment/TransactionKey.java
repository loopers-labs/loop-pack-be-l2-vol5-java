package com.loopers.domain.payment;

public record TransactionKey(String value) {

    public TransactionKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("거래 키가 비어 있습니다");
        }
    }
}
