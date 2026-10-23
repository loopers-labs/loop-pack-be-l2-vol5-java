package com.loopers.application.payment.pg;

/** PG 에 보내는 결제 요청. 금액은 저장된 주문 합계다. 카드 값은 합성 값만 쓰며 {@code toString} 에 드러내지 않는다. */
public record PgPaymentRequestCommand(String providerOrderId, String cardType, String cardNo, long amount) {
    @Override
    public String toString() {
        return "PgPaymentRequestCommand[providerOrderId=" + providerOrderId + ", cardType=" + cardType
            + ", cardNo=****, amount=" + amount + "]";
    }
}
