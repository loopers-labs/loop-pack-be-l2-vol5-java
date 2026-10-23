package com.loopers.domain.payment;

public record PaymentRequest(String providerOrderId, String cardType, String cardNo, long amount) {

    public PaymentRequest {
        if (providerOrderId == null || providerOrderId.length() < 6 || amount <= 0
            || cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new IllegalArgumentException("결제 요청이 올바르지 않습니다.");
        }
    }

    @Override
    public String toString() {
        return "PaymentRequest[providerOrderId=" + providerOrderId + ", amount=" + amount + ", card=REDACTED]";
    }
}
