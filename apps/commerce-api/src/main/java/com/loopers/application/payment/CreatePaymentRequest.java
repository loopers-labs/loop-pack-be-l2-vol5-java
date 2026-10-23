package com.loopers.application.payment;

/**
 * 거래 생성에 필요한 값. 카드 정보는 이 요청에만 담고 DB·로그에 남기지 않는다(과제 규칙) —
 * 그래서 toString에서 카드 번호를 가린다.
 */
public record CreatePaymentRequest(String providerOrderId, String cardType, String cardNo, long amount) {

    @Override
    public String toString() {
        return "CreatePaymentRequest[providerOrderId=" + providerOrderId + ", cardType=" + cardType
            + ", cardNo=****, amount=" + amount + "]";
    }
}
