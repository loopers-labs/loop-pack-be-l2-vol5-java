package com.loopers.application.order;

import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 주문 확정 사실 (OrderConfirmed). 최초 CONFIRMED 전이 때 한 번만 만든다.
 * 확정 트랜잭션 안에서 저장된 품목·수량·확정 시각으로 값을 다 채워 둔다. commit 뒤 발행할 때 엔티티(lazy 품목)를 다시 읽지 않는다.
 *
 * @param eventId     확정 사실에 고정된 ID. 주문당 확정은 한 번이라 orderId 에서 유도한다. 재발행·재전달에도 같다
 * @param confirmedAt 최초 확정 시각 (DB 의 confirmed_at 과 같은 값)
 * @param items       상품별 수량. 같은 상품은 주문 생성 때 합산돼 하나다 (INV-08). productId 오름차순
 */
public record OrderConfirmedEvent(String eventId, Long orderId, Instant confirmedAt, List<Item> items) {

    public record Item(Long productId, Integer quantity) {
    }

    public static String eventIdOf(Long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    /** CONFIRMED 로 막 전이한 주문에서 만든다. */
    public static OrderConfirmedEvent from(OrderModel order) {
        List<Item> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItemModel::getProductId))
            .map(item -> new Item(item.getProductId(), item.getQuantity()))
            .toList();
        return new OrderConfirmedEvent(eventIdOf(order.getId()), order.getId(), order.getConfirmedAt().toInstant(), items);
    }
}
