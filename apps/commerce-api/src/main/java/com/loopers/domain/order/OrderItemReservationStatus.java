package com.loopers.domain.order;

/**
 * 주문 품목 예약의 허용 전이만 표현한다. 재고 변경이나 DB 동시성 제어는 수행하지 않는다.
 * 반환된 상태를 저장할 때 실제 전이 여부와 재고 효과를 같은 트랜잭션에서 보호해야 한다.
 */
public enum OrderItemReservationStatus {
    NOT_RESERVED,
    HELD,
    CONSUMED,
    RELEASED;

    public OrderItemReservationStatus hold() {
        return transition(NOT_RESERVED, HELD);
    }

    public OrderItemReservationStatus consume() {
        return transition(HELD, CONSUMED);
    }

    public OrderItemReservationStatus release() {
        return transition(HELD, RELEASED);
    }

    private OrderItemReservationStatus transition(OrderItemReservationStatus source,
                                                   OrderItemReservationStatus target) {
        if (this != source && this != target) {
            throw new IllegalStateException("Reservation transition not allowed: " + this + " -> " + target);
        }
        return target;
    }
}
