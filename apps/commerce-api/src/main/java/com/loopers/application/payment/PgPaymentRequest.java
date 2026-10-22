package com.loopers.application.payment;

public record PgPaymentRequest(
    String providerOrderId,
    long amount,
    String callbackUrl
) {
    public static PgPaymentRequest of(String providerOrderId, long amount, String callbackUrl) {
        return new PgPaymentRequest(providerOrderId, amount, callbackUrl);
    }
}
