package com.loopers.payment.adapter.out.pg;

/**
 * 조회가 404가 아닌 4xx로 거절됐다 (요청·설정 잘못). 다시 보내도 같으므로 재시도하지 않고, PG 장애가 아니므로 회로 실패로 세지 않는다.
 * 어댑터 밖으로는 PgLookupUnavailableException으로 바꿔 내보낸다.
 */
class PgLookupClientErrorException extends RuntimeException {

    PgLookupClientErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
