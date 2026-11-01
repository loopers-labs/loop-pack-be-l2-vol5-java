package com.loopers.fixture.pg;

import com.loopers.pgsimulator.PaymentStatus;

/** state() 의 거래 관측. PG 저장소의 거래에서 callbackUrl 을 뺀 4필드다(5.5·5.7). */
public record TransactionSnapshot(String transactionKey, String orderId, long amount, PaymentStatus status) {
}
