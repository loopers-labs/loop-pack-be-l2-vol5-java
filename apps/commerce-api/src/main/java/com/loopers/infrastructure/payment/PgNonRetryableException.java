package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.UnavailableReason;

/** 다시 물어도 같을 조회 실패 (404 외의 4xx 등 계약 밖 응답). 우리 요청의 문제일 수 있어 PG 장애로 세지 않는다. */
final class PgNonRetryableException extends PgLookupException {

    PgNonRetryableException(UnavailableReason reason) {
        super(reason);
    }
}
