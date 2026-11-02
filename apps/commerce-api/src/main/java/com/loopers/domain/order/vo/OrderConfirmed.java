package com.loopers.domain.order.vo;

import com.loopers.domain.order.entity.Order;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 주문이 처음 확정됐다는 사실. commit 뒤 영속성 컨텍스트 밖에서 읽히므로 TX 안에서 엔티티 값을 복사해 둠 (6주차 D-72).
 * eventId 는 한 주문의 단 한 번인 확정에서 유도해 재발행해도 같음
 */
public record OrderConfirmed(String eventId, Long orderId, ZonedDateTime confirmedAt, List<Item> items) {

    private static final String EVENT_ID_PREFIX = "order-confirmed:v1:";

    public record Item(Long productId, int quantity) {}

    public OrderConfirmed {
        items = List.copyOf(items);
    }

    /** 품목은 주문 생성 때 이미 상품별로 합산돼 있음 (ORD-03) */
    public static OrderConfirmed from(Order order) {
        List<Item> items = order.getItems().stream()
            .map(item -> new Item(item.getProductId(), item.getQuantity()))
            .toList();
        return new OrderConfirmed(EVENT_ID_PREFIX + order.getId(), order.getId(), order.getConfirmedAt(), items);
    }
}
