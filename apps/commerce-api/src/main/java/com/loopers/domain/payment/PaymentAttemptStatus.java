package com.loopers.domain.payment;

public enum PaymentAttemptStatus {
    REQUESTED,
    PENDING,
    UNKNOWN,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
