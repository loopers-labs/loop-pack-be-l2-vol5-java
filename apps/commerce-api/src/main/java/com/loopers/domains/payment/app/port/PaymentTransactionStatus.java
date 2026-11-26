package com.loopers.domains.payment.app.port;

public enum PaymentTransactionStatus {
    PENDING,
    SUCCESS,
    FAILED,
    // 승인된 거래를 취소한 상태다. 과정 PG 계약에 없는 가정이다(w10s5-3).
    CANCELED
}
