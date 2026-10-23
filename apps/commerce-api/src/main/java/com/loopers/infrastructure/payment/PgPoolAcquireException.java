package com.loopers.infrastructure.payment;

/**
 * 원인 사슬에 정확한 타입의 ConnectionRequestTimeoutException 이 있는 PG 전용 풀 대기 실패.
 * 외부 PG 가용성이 아닌 우리 자원 부족이므로 회로 표본에서 제외한다(2.8). 이 타입만으로 생성 미발송을 확정하지 않는다.
 */
class PgPoolAcquireException extends RuntimeException {

    PgPoolAcquireException(Throwable cause) {
        super("PG 연결 풀 대기 시간 초과", cause);
    }
}
