package com.loopers.interfaces.api.payment;

public class PaymentV1Dto {
    public record PaymentCallbackRequest(String providerOrderId, String transactionKey, String status) {}
}
