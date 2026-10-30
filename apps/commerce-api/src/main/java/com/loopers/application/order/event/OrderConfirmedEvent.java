package com.loopers.application.order.event;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

public final class OrderConfirmedEvent {
    private static final String EVENT_ID_PREFIX = "order-confirmed:v1:";

    private final String eventId;
    private final Long orderId;
    private final ZonedDateTime confirmedAt;
    private final List<Item> items;

    private OrderConfirmedEvent(String eventId, Long orderId, ZonedDateTime confirmedAt, List<Item> items) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.confirmedAt = confirmedAt;
        this.items = items;
    }

    public static OrderConfirmedEvent from(Order order) {
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "확정 시각이 있는 확정 주문만 확정 이벤트를 만들 수 있습니다.");
        }
        List<Item> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .map(item -> new Item(item.getProductId(), item.getQuantity()))
            .toList();
        return new OrderConfirmedEvent(EVENT_ID_PREFIX + order.getId(), order.getId(), order.getConfirmedAt(), items);
    }

    public String getEventId() { return eventId; }
    public Long getOrderId() { return orderId; }
    public ZonedDateTime getConfirmedAt() { return confirmedAt; }
    public List<Item> getItems() { return items; }

    public record Item(Long productId, int quantity) {}
}
