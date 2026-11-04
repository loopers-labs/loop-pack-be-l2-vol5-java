package com.loopers.application.ordering.event;

import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record OrderConfirmedEvent(UUID eventId, long orderId, Instant confirmedAt, List<Item> items) {
    public static final String TYPE = "OrderConfirmed";
    public static final int VERSION = 1;

    public OrderConfirmedEvent {
        Objects.requireNonNull(eventId, "주문 이벤트 ID가 필요합니다.");
        Objects.requireNonNull(confirmedAt, "주문 확정 시각이 필요합니다.");
        items = List.copyOf(items);
        if (orderId <= 0 || confirmedAt.getNano() % 1_000 != 0 || items.isEmpty()) {
            throw new IllegalArgumentException("주문 ID, 확정 시각 또는 품목이 올바르지 않습니다.");
        }
        long previousProductId = 0;
        for (Item item : items) {
            if (item.productId() <= previousProductId) {
                throw new IllegalArgumentException("이벤트 품목은 상품별로 합산하고 오름차순으로 정렬해야 합니다.");
            }
            previousProductId = item.productId();
        }
    }

    public static OrderConfirmedEvent from(Order order) {
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getId() == null) {
            throw new IllegalArgumentException("저장된 확정 주문이 필요합니다.");
        }
        var record = order.getRecord().orElseThrow(() -> new IllegalArgumentException("확정 기록이 필요합니다."));
        if (record.getPaymentMethod() != PaymentMethod.PG || record.getStatus() != OrderRecordStatus.PAID
            || record.getId() == null || record.getConfirmationEventId() == null || record.getCreatedAt() == null) {
            throw new IllegalArgumentException("이벤트 ID가 저장된 PG 확정 기록이 필요합니다.");
        }
        var items = order.quantitiesByProductId().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new Item(entry.getKey(), entry.getValue()))
            .toList();
        return new OrderConfirmedEvent(record.getConfirmationEventId(), order.getId(), record.getCreatedAt(), items);
    }

    public record Item(long productId, int quantity) {
        public Item {
            if (productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("상품 ID와 수량은 양수여야 합니다.");
            }
        }
    }
}
