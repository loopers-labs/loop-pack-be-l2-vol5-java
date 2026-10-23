package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.UnavailableReason;

/** 다시 물으면 달라질 수 있는 조회 실패 (timeout, 연결 실패·끊김, 5xx, 형식 오류). PG 장애로 센다. */
final class PgRetryableException extends PgLookupException {

    PgRetryableException(UnavailableReason reason) {
        super(reason);
    }
}
