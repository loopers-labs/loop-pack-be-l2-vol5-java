package com.loopers.application.order.dto;

import com.loopers.domain.order.enums.OrderOutboxStatus;

/** resumed 가 false 면 BLOCKED 가 아니어서 아무것도 바꾸지 않았고, status 는 그 현재 상태임 */
public record OrderOutboxResumeInfo(String eventId, OrderOutboxStatus status, boolean resumed) {}
