package com.loopers.payment.application.port.out;

import com.loopers.payment.domain.ExternalPaymentStatus;

/**
 * PG가 알려 준 거래 한 건 (과제 2절 응답 필드). reason은 PG가 준 실패 사유이고 없으면 null.
 */
public record PgTransaction(String transactionKey, String providerOrderId, long amount, ExternalPaymentStatus status, String reason) {}
