package com.loopers.application.order;

import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflict;
import com.loopers.domain.payment.UnknownReason;

/**
 * 주문에 붙는 결제 시도의 상태. 카드번호는 저장하지 않아 담지 않는다.
 *
 * @param lookupUnavailable 이번 호출에서 PG에 거래를 물어봤지만 확인하지 못했다. 저장된 상태가 아니라 이 응답에만 해당한다.
 *                          결제를 실패로 정한다는 뜻이 아니고, 상태는 마지막으로 아는 그대로다
 */
public record PaymentInfo(
    PaymentAttemptStatus status,
    String transactionKey,
    UnknownReason unknownReason,
    PaymentConflict conflict,
    boolean lookupUnavailable
) {
    public static PaymentInfo from(PaymentAttemptModel attempt) {
        return new PaymentInfo(
            attempt.getStatus(), attempt.getTransactionKey(), attempt.getUnknownReason(), attempt.getConflict(), false
        );
    }

    public PaymentInfo withLookupUnavailable() {
        return new PaymentInfo(status, transactionKey, unknownReason, conflict, true);
    }
}
