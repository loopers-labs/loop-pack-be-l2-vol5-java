package com.loopers.application.activity;

import com.loopers.domain.activity.OrderConfirmation;

import java.time.Instant;
import java.util.List;

/** 메시지에서 읽은 주문 확정 사실. 계약 검증·정규화는 도메인(OrderConfirmation)이 한다. */
public record PurchaseActivityCommand(String eventId, Long orderId, Instant confirmedAt, List<Item> items) {

    public record Item(Long productId, Integer quantity) {
    }

    OrderConfirmation toConfirmation() {
        List<OrderConfirmation.Line> lines = items == null ? null
            : items.stream().map(item -> item == null ? null : new OrderConfirmation.Line(item.productId(), item.quantity())).toList();
        return new OrderConfirmation(eventId, orderId, confirmedAt, lines);
    }
}
