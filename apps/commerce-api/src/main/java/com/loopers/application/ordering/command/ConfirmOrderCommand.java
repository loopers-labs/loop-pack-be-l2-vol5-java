package com.loopers.application.ordering.command;

// 주문 확정 요청 커맨드
public record ConfirmOrderCommand(long orderId) {}
