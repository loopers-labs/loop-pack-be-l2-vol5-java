package com.loopers.application.payment;

/**
 * 조회 재시도 정책 — 경로마다 총 조회 횟수가 다르다(docs/week5/design.md D-028, recovery.md 2번).
 * 재시도는 고객이 기다리는 경로에서만 하고, 백그라운드 경로는 자기 방식(다음 확인 시각·try_count)으로만 다시 한다.
 * 층마다 재시도하면 호출 수가 곱해지기 때문이다. 실제 반복은 adapter가 하고, circuit breaker가 그 바깥을 감싼다(D-029).
 */
public enum LookupRetry {
    /** 스케줄러·수신함 워커. */
    NONE(1),
    /** confirm에서 create 응답을 잃은 직후의 바로 재조회(ⓐ). */
    IMMEDIATE(2),
    /** 고객 reconcile. */
    CUSTOMER(3);

    private final int maxAttempts;

    LookupRetry(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** 처음 시도를 포함한 총 조회 횟수. */
    public int maxAttempts() {
        return maxAttempts;
    }
}
