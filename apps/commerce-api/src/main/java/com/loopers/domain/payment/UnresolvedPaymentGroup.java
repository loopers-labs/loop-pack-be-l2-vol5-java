package com.loopers.domain.payment;

import java.time.ZonedDateTime;

/** 한 미종결 상태에 머문 시도의 수와 그중 가장 먼저 만든 시각. */
public record UnresolvedPaymentGroup(PaymentAttemptStatus status, long count, ZonedDateTime oldestCreatedAt) {
}
