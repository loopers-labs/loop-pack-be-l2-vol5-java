package com.loopers.interfaces.api.pg;

import java.util.List;

public class LocalPgDto {

    public record PaymentRequest(
        String orderId,
        String cardType,
        String cardNo,
        long amount,
        String callbackUrl
    ) {}

    public record PaymentResponse(
        String transactionKey,
        String orderId,
        long amount,
        String status
    ) {}

    public record RejectedResponse(
        String code,
        String orderId,
        long amount,
        boolean transactionCreated
    ) {}

    public record PaymentListResponse(List<PaymentResponse> transactions) {}
}
