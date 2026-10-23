package com.loopers.infrastructure.payment;

/**
 * 승인된 회복 대상 조회 실패: 연결·응답 timeout, 수신 I/O, 계약 외 HTTP 상태, 해석 불가(2.8 조회 분류표).
 * 회로에서는 실패 표본이고 회로 밖에서 QUERY 확인 불가로 바뀐다. 내부 풀 대기·프로그램 오류는 포함하지 않는다.
 */
class PgLookupUnavailableException extends RuntimeException {

    PgLookupUnavailableException(String message) {
        super(message);
    }

    PgLookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
