package com.loopers.domain.payment;

/**
 * 결제 시도의 상태. 허용하는 전이는 {@link #canMoveTo} 에 모여 있다.
 *
 * <p>SUCCEEDED 와 FAILED 는 종결 상태라 나가는 전이가 없다. PENDING 에서 UNKNOWN 으로 되돌아가지도 않는다.
 * 이미 나아간 상태를 늦게 도착한 응답이 되돌리지 못하게 하기 위해서다.
 */
public enum PaymentAttemptStatus {
    /** 발송을 시작한다고 DB 에 기록함. 실제 HTTP 발송 여부는 불확실하다 */
    REQUESTED,
    /** PG 가 거래를 만들었고 처리 중임을 확인함 (거래 키 있음) */
    PENDING,
    /** 요청은 보냈으나 거래 생성 여부를 확인하지 못함 */
    UNKNOWN,
    /** 검증한 SUCCESS */
    SUCCEEDED,
    /** 검증한 FAILED 또는 효과 0건 확정 거절 */
    FAILED;

    public boolean canMoveTo(PaymentAttemptStatus next) {
        return switch (this) {
            case REQUESTED -> next == PENDING || next == UNKNOWN || next == SUCCEEDED || next == FAILED;
            case PENDING -> next == SUCCEEDED || next == FAILED;
            case UNKNOWN -> next == PENDING || next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
