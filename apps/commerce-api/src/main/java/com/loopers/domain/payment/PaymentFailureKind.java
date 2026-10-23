package com.loopers.domain.payment;

/**
 * FAILED 의 원인. PG_FAILED 만 PG 거래가 있는 실패이고, 나머지는 외부 효과 0건(REJECTED_NO_EFFECT) 또는
 * 로컬의 확실한 미발송(CIRCUIT_REJECTED·POOL_ACQUIRE_TIMEOUT)이다(설계 3.6).
 */
public enum PaymentFailureKind {
    PG_FAILED,
    REJECTED_NO_EFFECT,
    CIRCUIT_REJECTED,
    POOL_ACQUIRE_TIMEOUT;

    public boolean hasNoExternalEffect() {
        return this != PG_FAILED;
    }

    static PaymentFailureKind of(LocalNotDispatchedReason reason) {
        return switch (reason) {
            case CIRCUIT_REJECTED -> CIRCUIT_REJECTED;
            case POOL_ACQUIRE_TIMEOUT -> POOL_ACQUIRE_TIMEOUT;
        };
    }
}
