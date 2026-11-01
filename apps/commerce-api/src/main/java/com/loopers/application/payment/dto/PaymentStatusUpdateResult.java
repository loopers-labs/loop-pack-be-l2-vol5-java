package com.loopers.application.payment.dto;

import com.loopers.application.order.dto.OrderConfirmedInfo;

/** PG 조회 결과를 반영한 결과. {@code confirmed} 는 이 반영이 주문을 처음 확정했을 때만 있고, 아니면 null 이다. */
public record PaymentStatusUpdateResult(PaymentAttemptInfo attempt, OrderConfirmedInfo confirmed) {}
