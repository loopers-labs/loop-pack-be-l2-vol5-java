package com.loopers.interfaces.api.payment;

public class PaymentCallbackV1Dto {

    /** PG가 보내는 결과 알림. transactionKey·status는 믿지 않고 PG 조회로 다시 확인한다. */
    public record CallbackRequest(String providerOrderId, String transactionKey, String status) {}
}
