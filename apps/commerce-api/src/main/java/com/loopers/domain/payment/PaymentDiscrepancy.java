package com.loopers.domain.payment;

public record PaymentDiscrepancy(Reason reason, TransactionKey observedKey, TransactionStatus observedStatus) {

    public enum Reason {
        MULTIPLE_TRANSACTIONS,
        AMOUNT_MISMATCH,
        ORDER_MISMATCH,
        RESULT_AFTER_SETTLED
    }
}
