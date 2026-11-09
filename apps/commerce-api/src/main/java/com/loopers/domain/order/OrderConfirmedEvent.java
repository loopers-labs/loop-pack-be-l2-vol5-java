package com.loopers.domain.order;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 주문이 처음 확정됐다는 사실 (W6 이벤트 계약, schemaVersion 1). 저장된 주문의 확정 시각·품목에서 만든다.
 *
 * <ul>
 *   <li>{@code eventId}는 한 주문의 단 한 번인 확정 사실에서 정해진다. 전송 시도·현재 시각·무작위 값을 넣지 않아, 재발행·재전달에도 같다.</li>
 *   <li>{@code items}는 상품별로 합산해 하나로 만들고 상품 id 순으로 둔다. 같은 사실이면 항상 같은 모양이다.</li>
 *   <li>카드·PG 거래 정보·사용자 정보는 담지 않는다.</li>
 * </ul>
 *
 * @param confirmedAt 최초 확정 시각(UTC, ISO-8601). 저장된 주문의 값과 같다
 */
public record OrderConfirmedEvent(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {
    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public record Item(Long productId, int quantity) {
    }

    /** 확정된 주문에서 이벤트를 만든다. 확정 전이거나 확정 시각이 없는 주문(4주차 이전의 POINT 확정 등)에는 만들 수 없다. */
    public static OrderConfirmedEvent from(OrderModel order) {
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
            throw new IllegalStateException("확정 시각이 기록된 확정 주문만 이벤트로 만들 수 있습니다. orderId=" + order.getId());
        }
        return new OrderConfirmedEvent(
            EVENT_TYPE,
            SCHEMA_VERSION,
            eventIdOf(order.getId()),
            order.getId(),
            order.getConfirmedAt().toString(),
            normalize(order.getItems())
        );
    }

    public static String eventIdOf(Long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    /** Kafka record key. 같은 주문은 항상 같은 key라서 같은 partition으로 간다. */
    public String key() {
        return String.valueOf(orderId);
    }

    // 같은 상품은 수량을 합치고, 상품 id 오름차순으로 둔다
    private static List<Item> normalize(List<OrderItem> items) {
        Map<Long, Integer> quantityByProduct = new TreeMap<>();
        for (OrderItem item : items) {
            quantityByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        return quantityByProduct.entrySet().stream()
            .map(entry -> new Item(entry.getKey(), entry.getValue()))
            .toList();
    }
}
