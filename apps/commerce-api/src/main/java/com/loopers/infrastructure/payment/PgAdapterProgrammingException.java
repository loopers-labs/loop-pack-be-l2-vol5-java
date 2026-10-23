package com.loopers.infrastructure.payment;

/**
 * 응답 계약 검증을 통과한 뒤 adapter 내부에서 식별한 프로그램 오류. 회로 표본에서 제외하고 500 으로 전파한다(2.8).
 * 알려진 통신·해석 오류를 감싸는 데 쓰지 않는다.
 */
class PgAdapterProgrammingException extends RuntimeException {

    PgAdapterProgrammingException(String message, Throwable cause) {
        super(message, cause);
    }
}
