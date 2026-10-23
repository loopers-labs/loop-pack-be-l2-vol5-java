package com.loopers.domain.payment;

import com.loopers.domain.common.Money;

public record PaymentDiscrepancy(
    Reason reason,
    TransactionKey observedKey,
    TransactionStatus observedStatus,
    Money observedAmount
) {

    public enum Reason {
        MULTIPLE_TRANSACTIONS,
        AMOUNT_MISMATCH,
        ORDER_MISMATCH,
        RESULT_AFTER_SETTLED
    }
}
