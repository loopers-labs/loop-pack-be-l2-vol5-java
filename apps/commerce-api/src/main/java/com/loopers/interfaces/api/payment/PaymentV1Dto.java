package com.loopers.interfaces.api.payment;

public class PaymentV1Dto {

    /** 테스트 PG 콜백 본문. orderId 만 확인 계기로 쓰고 transactionKey · status 는 믿지 않음 (D-62) */
    public record CallbackRequest(String orderId, String transactionKey, String status) {}
}
