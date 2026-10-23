package com.loopers.domain.payment;

/** PG 거래 객체의 상태. 저장 결제 상태(UNKNOWN 포함)와 별개다. */
public enum PgTransactionStatus {
    PENDING,
    SUCCESS,
    FAILED
}
