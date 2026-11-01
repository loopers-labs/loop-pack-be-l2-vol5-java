package com.loopers.application.order.dto;

import com.loopers.domain.order.Order;

import java.time.ZonedDateTime;
import java.util.List;

/** 주문이 처음 확정됐다는 사실. 저장된 주문의 품목·수량·확정 시각으로 만들며, 트랜잭션 밖에서도 안전한 값이다. */
public record OrderConfirmedInfo(String eventId, Long orderId, ZonedDateTime confirmedAt, List<Item> items) {

    public record Item(Long productId, int quantity) {}

    // 한 주문의 확정은 한 번뿐이므로 주문 ID 만으로 유도해 재발행·재전달에서도 같은 값이 된다.
    public static OrderConfirmedInfo from(Order order) {
        return new OrderConfirmedInfo(
            "order-confirmed:v1:" + order.getId(),
            order.getId(),
            order.getConfirmedAt(),
            order.getItems().stream().map(item -> new Item(item.getProductId(), item.getQuantity())).toList()
        );
    }
}
