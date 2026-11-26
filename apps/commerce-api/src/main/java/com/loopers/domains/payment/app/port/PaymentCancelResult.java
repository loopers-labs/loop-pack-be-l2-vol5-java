package com.loopers.domains.payment.app.port;

// 분류 기준은 create와 같다: 의심스러우면 Unknown이다.
public sealed interface PaymentCancelResult {
    // 요청이 PG에 닿지 않았음이 확실할 때만 쓴다.
    record NotSent(String reason) implements PaymentCancelResult {
    }

    record Canceled(PaymentTransaction transaction) implements PaymentCancelResult {
    }

    // PG가 취소하지 않았다고 명시한 경우만 쓴다. 단순 거절·오류 응답은 Unknown이다.
    record RejectedNoEffect(String code) implements PaymentCancelResult {
    }

    // reason에는 응답 본문을 담지 않는다.
    record Unknown(String reason) implements PaymentCancelResult {
    }
}
