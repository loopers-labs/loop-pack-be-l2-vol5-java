package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * OrderConfirmed 이벤트에 실을 사실(docs/week6/design.md 2번 이벤트 계약). 결과 반영 트랜잭션 안에서 처음 확정 직후
 * 만든다 — 같은 트랜잭션이라 이 값이 곧 commit될 값이다(docs/week6/decisions.md D-5, INV-602).
 *
 * - 불변 객체다. afterCommit과 발행 스레드에는 이 객체만 넘기고 entity는 넘기지 않는다 — 발행의 정확성이 다른 파일의
 *   fetch 설정에 달리지 않게 한다.
 * - eventId는 orderId로만 정한다(`order-confirmed:v1:{orderId}`). 재발행·재전달에도 그대로다(D-18).
 * - 카드·PG·개인정보는 담지 않는다.
 */
public record OrderConfirmedPayload(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    ZonedDateTime confirmedAt,
    List<Item> items
) {

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public OrderConfirmedPayload {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        items = List.copyOf(items);
    }

    /** 확정된 주문에서 만든다. 품목은 productId 오름차순으로 복사한다(같은 productId 병합·양수 수량은 Order가 이미 보장한다). */
    public static OrderConfirmedPayload from(Order order) {
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
            throw new IllegalStateException("[orderId = " + order.getId() + "] 확정되지 않은 주문으로 확정 이벤트를 만들 수 없습니다.");
        }
        List<Item> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .map(item -> new Item(item.getProductId(), item.getQuantity()))
            .toList();
        return new OrderConfirmedPayload(EVENT_TYPE, SCHEMA_VERSION, eventIdOf(order.getId()), order.getId(),
            order.getConfirmedAt(), items);
    }

    public static String eventIdOf(Long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    public record Item(Long productId, int quantity) {}
}
