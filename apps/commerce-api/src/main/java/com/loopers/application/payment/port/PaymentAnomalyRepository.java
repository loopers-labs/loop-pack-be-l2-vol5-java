package com.loopers.application.payment.port;

import java.time.Instant;

public interface PaymentAnomalyRepository {
    enum Kind {
        LOOKUP_FAILED, NO_TRANSACTION, CALLBACK_KEY_MISMATCH, TRANSACTION_MISMATCH,
        DUPLICATE_TRANSACTIONS, COVERAGE_CONTRACT_VIOLATION, LOOKUP_LIMIT_EXHAUSTED, CONFLICTING_RESULT, RESULT_BEFORE_PAYMENT
    }

    void record(long orderId, Kind kind, String summary, Instant now);

    void resolveTransient(long orderId, Instant now);
}
