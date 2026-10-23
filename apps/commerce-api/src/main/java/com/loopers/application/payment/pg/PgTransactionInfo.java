package com.loopers.application.payment.pg;

/** PG 의 거래 한 건. {@code providerOrderId} 는 우리가 요청에 실어 보낸 주문 식별자다. */
public record PgTransactionInfo(String transactionKey, String providerOrderId, long amount, PgStatus status) {}
