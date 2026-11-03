package com.loopers.domain.order;

import com.loopers.domain.event.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 생산자가 보낼 주문 확정 사실의 불변 스냅샷이다. 엔티티나 지연 로딩 상태를 보관하지 않는다.
 * 스냅샷 생성은 최초 전이 또는 DB commit의 증거가 아니며, 실제 발행 시점은 트랜잭션 연결에서 보호한다.
 * 생산자 전용 계약이며, 다른 eventId를 가진 수신 메시지의 충돌 판정은 소비자 경계의 책임이다.
 */
public record OrderConfirmedEvent(String eventType, int schemaVersion, String eventId, long orderId,
                                  Instant confirmedAt, List<Item> items) implements DomainEvent {
    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int SCHEMA_VERSION = 1;

    public OrderConfirmedEvent {
        if (!EVENT_TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported order confirmation event contract");
        }
        if (orderId <= 0 || !eventIdFor(orderId).equals(eventId) || confirmedAt == null) {
            throw new IllegalArgumentException("Order confirmation requires a canonical identity and first confirmation time");
        }
        items = normalize(items);
    }

    private static List<Item> normalize(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Order confirmation requires purchased items");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Item item : items) {
            if (item == null) {
                throw new IllegalArgumentException("Order confirmation item must not be null");
            }
            try {
                quantities.merge(item.productId(), item.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Combined purchase quantity exceeds its supported range", exception);
            }
        }
        return quantities.entrySet().stream().map(entry -> new Item(entry.getKey(), entry.getValue())).toList();
    }

    /** 트랜잭션 안에서 저장할 주문의 최초 시각·품목을 값으로 복사한다. */
    public static OrderConfirmedEvent from(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Source order must not be null");
        }
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getPaymentMethod() != OrderPaymentMethod.PG
            || order.getId() == null || order.getId() <= 0) {
            throw new IllegalStateException("Only an identified confirmed PG order can supply this event");
        }
        order.validatePgPaymentState();
        return new OrderConfirmedEvent(EVENT_TYPE, SCHEMA_VERSION, eventIdFor(order.getId()), order.getId(),
            order.getConfirmedAt().toInstant(), order.getItems().stream()
                .map(OrderConfirmedEvent::snapshot).toList());
    }

    private static Item snapshot(OrderItem item) {
        Long productId = item.getProductId();
        if (productId == null || productId <= 0) {
            throw new IllegalStateException("Purchased item must have a stored product identity");
        }
        return new Item(productId, item.getQuantity());
    }

    private static String eventIdFor(long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    public String key() {
        return Long.toString(orderId);
    }

    public record Item(long productId, int quantity) {
        public Item {
            if (productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("Purchased item requires a positive product identity and quantity");
            }
        }
    }
}
