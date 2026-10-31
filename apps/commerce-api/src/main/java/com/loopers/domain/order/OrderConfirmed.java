package com.loopers.domain.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 최초 PG 주문 확정 시점의 불변 업무 사실. Entity나 현재 상품 정보에 의존하지 않는다. */
public record OrderConfirmed(Long orderId, Instant confirmedAt, List<Item> items) {

    public OrderConfirmed {
        if (orderId == null || orderId <= 0 || confirmedAt == null || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("주문 확정 사실이 올바르지 않습니다.");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Item item : items) {
            if (item == null) {
                throw new IllegalArgumentException("주문 확정 품목이 필요합니다.");
            }
            quantities.merge(item.productId(), item.quantity(), Math::addExact);
        }
        items = quantities.entrySet().stream().map(entry -> new Item(entry.getKey(), entry.getValue())).toList();
    }

    public String eventId() {
        return "order-confirmed:v1:" + orderId;
    }

    public record Item(Long productId, int quantity) {
        public Item {
            if (productId == null || productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("주문 확정 품목이 올바르지 않습니다.");
            }
        }
    }
}
