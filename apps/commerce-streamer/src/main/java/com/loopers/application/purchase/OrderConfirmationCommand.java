package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderPurchase;

import java.time.Instant;
import java.util.List;

/** 수신 계층이 사용하는 application 입력. 도메인 객체는 application 안에서만 꺼낸다. */
public final class OrderConfirmationCommand {
    private final OrderPurchase purchase;

    public OrderConfirmationCommand(String eventId, Long orderId, Instant confirmedAt, List<Item> items) {
        List<OrderPurchase.Item> facts = items == null ? null : items.stream()
            .map(item -> item == null ? null : new OrderPurchase.Item(item.productId(), item.quantity())).toList();
        purchase = new OrderPurchase(eventId, orderId, confirmedAt, facts);
    }

    OrderPurchase purchase() {
        return purchase;
    }

    public String eventId() {
        return purchase.eventId();
    }

    public Long orderId() {
        return purchase.orderId();
    }

    public Instant confirmedAt() {
        return purchase.confirmedAt();
    }

    public List<Item> items() {
        return purchase.items().stream().map(item -> new Item(item.productId(), item.quantity())).toList();
    }

    public record Item(Long productId, int quantity) {}
}
