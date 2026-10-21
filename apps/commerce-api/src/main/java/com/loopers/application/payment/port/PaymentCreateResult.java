package com.loopers.application.payment.port;

// 분류 기준(설계 21절): 의심스러우면 Unknown이다.
public sealed interface PaymentCreateResult {
    // 요청이 PG에 닿지 않았음이 확실할 때만 쓴다.
    record NotSent(String reason) implements PaymentCreateResult {
    }

    record Accepted(PaymentTransaction transaction) implements PaymentCreateResult {
    }

    // PG가 거래를 만들지 않았다고 명시한 경우만 쓴다. 단순 거절·오류 응답은 Unknown이다.
    record RejectedNoEffect(String code) implements PaymentCreateResult {
    }

    // reason에는 카드 번호·응답 본문을 담지 않는다.
    record Unknown(String reason) implements PaymentCreateResult {
    }
}
