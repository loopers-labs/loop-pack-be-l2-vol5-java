package com.loopers.domain.payment;

/**
 * TX A 가 확정한 불변 생성 요청. 카드 값은 이번 confirm 입력의 합성 값이며 저장하지 않고 문자열 표현에도 넣지 않는다.
 * 콜백 주소·제공자 계정은 서버 설정이 정한다.
 */
public record PaymentRequest(String providerOrderId, long amount, String cardType, String cardNo) {

    public PaymentRequest {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("providerOrderId 필수");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount 는 양수");
        }
        if (cardType == null || cardNo == null) {
            throw new IllegalArgumentException("카드 값 필수");
        }
    }

    @Override
    public String toString() {
        return "PaymentRequest[providerOrderId=" + providerOrderId + ", amount=" + amount + "]";
    }
}
