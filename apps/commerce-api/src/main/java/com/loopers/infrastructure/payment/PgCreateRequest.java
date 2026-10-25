package com.loopers.infrastructure.payment;

// 테스트 PG 생성 요청 본문이다. HTTP client가 디버그 로그에 본문을 toString으로 남기므로 카드 값을 가린다.
record PgCreateRequest(String orderId, String cardType, String cardNo, long amount, String callbackUrl) {

    @Override
    public String toString() {
        return "PgCreateRequest[orderId=" + orderId + ", amount=" + amount + ", card=redacted]";
    }
}
