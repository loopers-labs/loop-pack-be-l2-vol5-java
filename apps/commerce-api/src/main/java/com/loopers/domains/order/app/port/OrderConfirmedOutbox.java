package com.loopers.domains.order.app.port;

public interface OrderConfirmedOutbox {
    // 주문을 확정한 트랜잭션 안에서 부른다. 실패하면 예외로 올려 확정까지 함께 rollback되게 한다.
    void append(OrderConfirmedPayload payload);
}
