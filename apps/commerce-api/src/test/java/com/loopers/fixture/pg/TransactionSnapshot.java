package com.loopers.fixture.pg;

/** PG 응답과 관측에 함께 쓴다. 저장한 callbackUrl 은 노출하지 않는다(5.5·5.7). */
public record TransactionSnapshot(String transactionKey, String orderId, long amount, PaymentStatus status) {
}
