package com.loopers.domain.order;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 주문 최초 확정 사실의 발행 payload (6주차 설계 1절·D1·D9). 필드 순서는 과제 p.2 샘플 JSON 과 같다.
 * - eventId 는 orderId 에서 계산하므로 재발행·재전달 때도 바뀌지 않는다
 * - confirmedAt 은 UTC Instant 라 "2026-09-07T01:00:00.123Z" 형식으로 직렬화된다 (Asia/Seoul 시각을 그대로 내보내지 않는다)
 * - items 는 같은 상품을 합산하고 productId 오름차순으로 정규화한다
 */
public record OrderConfirmedEvent(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    Instant confirmedAt,
    List<Item> items
) {
    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public record Item(Long productId, int quantity) {
    }

    /**
     * @param confirmedAt 서비스가 Clock 으로 만들어 주문 UPDATE 에 쓴 바로 그 값. 엔티티에서 다시 읽지 않는다
     * @param orderItems  DB 에서 읽은 저장 품목
     */
    public static OrderConfirmedEvent of(Long orderId, ZonedDateTime confirmedAt, List<OrderItem> orderItems) {
        Map<Long, Integer> quantityByProduct = new TreeMap<>();
        for (OrderItem item : orderItems) {
            quantityByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        List<Item> items = quantityByProduct.entrySet().stream()
            .map(entry -> new Item(entry.getKey(), entry.getValue()))
            .toList();
        return new OrderConfirmedEvent(EVENT_TYPE, SCHEMA_VERSION, eventIdOf(orderId), orderId, confirmedAt.toInstant(), items);
    }

    public static String eventIdOf(Long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    /** record key. payload 의 orderId 와 같은 문자열이다. */
    public String key() {
        return String.valueOf(orderId);
    }
}
