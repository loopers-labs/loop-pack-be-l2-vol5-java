package com.loopers.domain.ordering.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrderConfirmation(UUID eventId, long orderId, Instant confirmedAt, List<Item> items) {
    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public OrderConfirmation {
        Objects.requireNonNull(eventId, "확정 이벤트 ID가 필요합니다.");
        Objects.requireNonNull(confirmedAt, "주문 확정 시각이 필요합니다.");
        items = List.copyOf(items);
        if (orderId <= 0 || confirmedAt.getNano() % 1_000 != 0 || items.isEmpty()) {
            throw new IllegalArgumentException("주문 ID, 확정 시각 또는 품목이 올바르지 않습니다.");
        }
        long previousProductId = 0;
        for (Item item : items) {
            if (item.productId() <= previousProductId) {
                throw new IllegalArgumentException("품목을 상품별로 합산하고 오름차순으로 정렬해야 합니다.");
            }
            previousProductId = item.productId();
        }
    }

    public record Item(long productId, int quantity) {
        public Item {
            if (productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("상품 ID와 수량은 양수여야 합니다.");
            }
        }
    }
}
