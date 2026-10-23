package com.loopers.payment.application.port.out;

/**
 * PG 거래 생성 요청. 카드 번호는 이 객체로 PG에 전달될 뿐 저장·로그하지 않는다 (plan.md 8장).
 */
public record PgPaymentRequest(String providerOrderId, String cardType, String cardNo, long amount) {

    @Override
    public String toString() {
        return "PgPaymentRequest[providerOrderId=" + providerOrderId + ", cardType=" + cardType + ", amount=" + amount + "]";
    }
}
