package com.loopers.domain.payment;

/** Transient input from the resolved owner and stored order; never persist or log the card. */
public record PaymentFingerprintInput(long userId, long orderId, long storedAmount, String cardType, String cardNo) {
    public PaymentFingerprintInput {
        if (userId <= 0L || orderId <= 0L || storedAmount < 0L
            || cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new IllegalArgumentException("Invalid payment fingerprint input");
        }
    }

    @Override
    public String toString() {
        return "PaymentFingerprintInput[REDACTED]";
    }
}
