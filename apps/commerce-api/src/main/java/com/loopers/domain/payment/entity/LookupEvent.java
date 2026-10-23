package com.loopers.domain.payment.entity;

import java.util.Objects;
import com.loopers.domain.payment.enums.PaymentConflict;

/** 조회 결과 판정. 저장값과의 비교를 건너뛰지 못하도록 package-private 으로 두고 Payment 만 만듦 */
enum LookupEvent {
    /** 거래 1건, PENDING */
    PENDING(null),
    /** 거래 1건, SUCCESS */
    SUCCESS(null),
    /** 거래 1건, FAILED */
    FAILED(null),
    /** 거래 0건 */
    NONE(null),
    /** 거래 2건 이상 */
    MULTIPLE(PaymentConflict.MULTIPLE_TRANSACTIONS),
    /** 주문 · 금액 불일치, 또는 저장된 거래 키와 다름 */
    MISMATCHED(PaymentConflict.MISMATCHED_TRANSACTION);

    private final PaymentConflict conflict;

    LookupEvent(PaymentConflict conflict) {
        this.conflict = conflict;
    }

    boolean isConflict() {
        return conflict != null;
    }

    PaymentConflict conflict() {
        return Objects.requireNonNull(conflict);
    }
}
