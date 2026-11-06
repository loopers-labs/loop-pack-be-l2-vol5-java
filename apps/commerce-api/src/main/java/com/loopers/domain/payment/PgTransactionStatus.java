package com.loopers.domain.payment;

/** PG가 알려 주는 거래 상태. PENDING은 PG가 처리 중이라고 알려 준 상태이고, 결제 결과가 아니다. */
public enum PgTransactionStatus {
    PENDING,
    SUCCESS,
    FAILED
}
