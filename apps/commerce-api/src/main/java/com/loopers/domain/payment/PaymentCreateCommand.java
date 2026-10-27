package com.loopers.domain.payment;

/**
 * PG 거래 생성 요청. 카드 번호는 PG 로 보내기 위해서만 들고 있으며, 저장·로그에 남기지 않는다.
 */
public record PaymentCreateCommand(String providerOrderId, String cardType, String cardNo, long amount) {

    @Override
    public String toString() {
        return "PaymentCreateCommand[providerOrderId=" + providerOrderId
            + ", cardType=" + cardType
            + ", cardNo=****"
            + ", amount=" + amount + "]";
    }
}