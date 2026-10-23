package com.loopers.domain.payment;

/** Transient synthetic PG input; never persist or log the card or serialized body. */
public record PaymentCreateRequest(String providerOrderId, long amount, String cardType, String cardNo) {
    public PaymentCreateRequest {
        if (providerOrderId == null || providerOrderId.isBlank() || providerOrderId.length() < 6
            || amount < 0 || cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new IllegalArgumentException("Invalid test PG creation input");
        }
    }

    @Override
    public String toString() {
        return "PaymentCreateRequest[card=REDACTED]";
    }
}
