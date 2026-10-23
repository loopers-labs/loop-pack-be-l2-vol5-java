package com.loopers.domain.payment;

public enum PaymentStatus {
    PENDING,
    UNKNOWN,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
