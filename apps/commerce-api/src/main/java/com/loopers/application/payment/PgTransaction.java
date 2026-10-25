package com.loopers.application.payment;

public record PgTransaction(String transactionKey, String providerOrderId, long amount, PgTransactionStatus status) {}
