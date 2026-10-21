package com.loopers.payment.adapter.out.pg;

/**
 * 로컬 테스트 PG 생성 요청 본문 (과제 2절). 카드 번호가 로그에 남지 않게 toString에서 뺀다 (plan.md 8장).
 */
record PgCreateBody(String orderId, String cardType, String cardNo, long amount, String callbackUrl) {

    @Override
    public String toString() {
        return "PgCreateBody[orderId=" + orderId + ", cardType=" + cardType + ", amount=" + amount + "]";
    }
}
