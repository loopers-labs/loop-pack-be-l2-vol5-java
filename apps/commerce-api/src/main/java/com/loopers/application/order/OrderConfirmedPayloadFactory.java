package com.loopers.application.order;

import com.loopers.domain.order.Order;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 최초 발행과 3b 재발행이 같은 payload를 만들도록 생성 규칙을 여기 한 곳에 둔다. 저장된 주문 값만 쓰고 상품·가격을 조회하지 않는다.
public final class OrderConfirmedPayloadFactory {
    // 주문은 한 번만 CONFIRMED가 된다는 전제의 규칙이다. 재확정이 생기면 v2 + 시도 번호로 바꾼다(설계 27절 D79).
    private static final String EVENT_ID_PREFIX = "order-confirmed:v1:";

    private OrderConfirmedPayloadFactory() {
    }

    public static OrderConfirmedPayload create(Order order) {
        if (order.getStatus() != Order.Status.CONFIRMED || order.getConfirmedAt() == null) {
            throw new IllegalStateException("확정 시각이 있는 CONFIRMED 주문만 확정 이벤트를 만들 수 있습니다.");
        }
        Map<Long, Integer> quantities = order.getItems().stream()
            .collect(Collectors.toMap(item -> item.productId().value(), item -> item.quantity().value(), Integer::sum,
                TreeMap::new));
        List<OrderConfirmedPayload.Item> items = quantities.entrySet().stream()
            .map(entry -> new OrderConfirmedPayload.Item(entry.getKey(), entry.getValue()))
            .toList();
        long orderId = order.getId();
        return new OrderConfirmedPayload(String.valueOf(orderId), EVENT_ID_PREFIX + orderId, orderId, order.getConfirmedAt(),
            items);
    }
}
