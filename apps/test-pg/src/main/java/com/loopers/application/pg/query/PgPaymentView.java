package com.loopers.application.pg.query;

public record PgPaymentView(String transactionKey, String orderId, long amount, String status) {}
