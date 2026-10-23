package com.loopers.infrastructure.payment;

/**
 * 생성 결과를 확인하지 못한 실패: 연결·응답 timeout, 수신 I/O, 해석 불가, 계약 외 HTTP 상태(2.8 생성 분류표).
 * PG 가 거래를 만들었을 수 있으므로 효과 0건이나 미발송의 증거로 쓰지 않는다.
 */
class PgCreateUnavailableException extends RuntimeException {

    PgCreateUnavailableException(String message) {
        super(message);
    }

    PgCreateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
