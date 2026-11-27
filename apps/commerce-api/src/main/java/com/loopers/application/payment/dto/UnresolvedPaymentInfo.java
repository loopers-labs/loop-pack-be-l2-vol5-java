package com.loopers.application.payment.dto;

import com.loopers.domain.payment.PaymentAttemptStatus;

/** 한 미종결 상태의 시도 수와 가장 오래 기다린 시도의 대기시간(초). */
public record UnresolvedPaymentInfo(PaymentAttemptStatus status, long count, long oldestAgeSeconds) {
}
