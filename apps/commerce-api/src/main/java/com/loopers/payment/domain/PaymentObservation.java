package com.loopers.payment.domain;

import com.loopers.common.domain.Money;

/**
 * PG 권위 조회가 알려 준 거래 한 건 (plan.md 3-3·4). 도메인은 application을 모르므로 PgTransaction 대신 이 값을 받는다.
 * reason은 PG가 준 실패 사유이고 없으면 null.
 */
public record PaymentObservation(
    String transactionKey,
    String providerOrderId,
    Money amount,
    ExternalPaymentStatus status,
    String reason
) {}
