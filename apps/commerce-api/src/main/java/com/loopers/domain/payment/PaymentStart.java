package com.loopers.domain.payment;

/**
 * TX A 결과. STARTED 면 이 실행이 발송 권한을 얻었으므로 PG create 를 1회 호출한다.
 * EXISTING 이면 같은 키·같은 요청의 재요청이므로 저장된 상태만 돌려주고 PG 를 호출하지 않는다.
 */
public record PaymentStart(Kind kind, Long attemptId, String providerOrderId, long amount, String cardType) {

    public enum Kind {
        STARTED, EXISTING
    }

    public static PaymentStart started(PaymentAttempt attempt) {
        return of(Kind.STARTED, attempt);
    }

    public static PaymentStart existing(PaymentAttempt attempt) {
        return of(Kind.EXISTING, attempt);
    }

    public boolean isStarted() {
        return kind == Kind.STARTED;
    }

    private static PaymentStart of(Kind kind, PaymentAttempt attempt) {
        return new PaymentStart(
            kind, attempt.getId(), attempt.getProviderOrderId(), attempt.getAmount(), attempt.getCardType());
    }
}