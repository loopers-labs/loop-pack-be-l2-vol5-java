package com.loopers.domain.payment;

public enum PaymentAttemptStatus {
    REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
