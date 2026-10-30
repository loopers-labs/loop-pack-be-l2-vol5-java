package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 최초 PG 확정 사실을 담은 불변 값. 소비자에게 보내는 작업 지시가 아니다.
 * TX 밖에서도 안전하게 쓰도록 Entity·Spring·Kafka 객체를 보관하지 않는다. schemaVersion·topic·key 는 발행 adapter 가 정한다.
 */
public record OrderConfirmed(long orderId, ZonedDateTime confirmedAt, List<Item> items) {

    private static final String EVENT_ID_PREFIX = "order-confirmed:";

    public OrderConfirmed {
        if (orderId < 1L || !OrderModel.isUtcMicros(confirmedAt) || items == null || items.isEmpty()) {
            throw integrityError("확정 사건의 주문 ID·시각·품목이 올바르지 않습니다.");
        }
        items = List.copyOf(items);
    }

    /**
     * TX 안에서 저장된 주문 사실만 복사한다. 과거 사실이므로 상품의 현재 존재·노출·가격은 보지 않는다.
     * 원본 항목의 수량은 합산 전에 각각 검사한다. 합계만 보면 같은 상품의 -1 과 3 이 정상 수량 2 로 가려진다.
     */
    public static OrderConfirmed from(OrderModel order) {
        if (order.getPaymentMethod() != PaymentMethod.PG || order.getStatus() != OrderStatus.CONFIRMED) {
            throw integrityError("PG 확정 주문이 아닙니다. [status = " + order.getStatus() + "]");
        }
        if (!OrderModel.isUtcMicros(order.getConfirmedAt())) {
            throw integrityError("PG 확정 주문의 확정 시각이 올바르지 않습니다.");
        }
        Long orderId = order.getId();
        if (orderId == null || orderId < 1L) {
            throw integrityError("확정 주문의 ID 가 올바르지 않습니다.");
        }
        List<OrderItemModel> stored = order.getItems();
        if (stored.isEmpty()) {
            throw integrityError("확정 주문에 품목이 없습니다.");
        }
        for (OrderItemModel item : stored) {
            if (item.getProductId() == null || item.getProductId() < 1L || item.getQuantity() < 1L) {
                throw integrityError("확정 주문의 품목 상품 ID·수량이 올바르지 않습니다.");
            }
        }
        Map<Long, Long> quantities = new TreeMap<>();
        try {
            for (OrderItemModel item : stored) {
                quantities.merge(item.getProductId(), item.getQuantity(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            throw integrityError("확정 주문의 상품별 수량 합계가 표현 범위를 넘습니다.");
        }
        List<Item> items = quantities.entrySet().stream()
            .map(entry -> new Item(entry.getKey(), entry.getValue()))
            .toList();
        return new OrderConfirmed(orderId, order.getConfirmedAt(), items);
    }

    /** 주문당 최초 확정은 한 번이므로 주문 ID 로만 정한다. 재전달에도 같아야 해서 UUID·시각·전송 횟수·schemaVersion 을 섞지 않는다. */
    public String eventId() {
        return EVENT_ID_PREFIX + Long.toString(orderId);
    }

    public record Item(long productId, long quantity) {

        public Item {
            if (productId < 1L || quantity < 1L) {
                throw integrityError("확정 사건 품목의 상품 ID·수량이 올바르지 않습니다.");
            }
        }
    }

    private static CoreException integrityError(String message) {
        return new CoreException(ErrorType.INTERNAL_ERROR, message);
    }
}
