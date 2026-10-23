package com.loopers.batch.payment;

public enum RemotePaymentState {
    SUCCESS,
    FAILED,
    CANCELED,
    PARTIALLY_CANCELED,
    PROCESSING,
    UNKNOWN
}
