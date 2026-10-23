package com.loopers.domain.payment.gateway;

/** 카드 정보는 저장하지 않고, 로그로 새지 않도록 toString 에서 번호를 가림 */
public record PaymentGatewayRequest(String providerOrderId, String cardType, String cardNo, long amount) {

    @Override
    public String toString() {
        return "PaymentGatewayRequest[providerOrderId=" + providerOrderId
            + ", cardType=" + cardType + ", cardNo=****, amount=" + amount + "]";
    }
}
