package com.loopers.domain.payment;

public record PaymentTransaction(String transactionKey, String providerOrderId, long amount, Status status) {

    public PaymentTransaction {
        if (transactionKey == null || transactionKey.isBlank() || providerOrderId == null || providerOrderId.length() < 6
            || amount <= 0 || status == null) {
            throw new IllegalArgumentException("PG 거래 정보가 올바르지 않습니다.");
        }
    }

    public enum Status { PENDING, SUCCESS, FAILED }
}
