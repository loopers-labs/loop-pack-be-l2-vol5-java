package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.UnavailableReason;

/**
 * 조회 한 번이 실패했다는 adapter 내부 신호. 재조회·회로 차단기가 예외 종류로 실패를 구분하게 하려는 용도이며,
 * adapter 밖으로는 나가지 않고 {@link com.loopers.domain.payment.PgLookupResult.Unavailable}로 바뀐다.
 */
abstract class PgLookupException extends RuntimeException {

    private final UnavailableReason reason;

    PgLookupException(UnavailableReason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    UnavailableReason reason() {
        return reason;
    }
}
