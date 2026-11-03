package com.loopers.domains.order.app.port;

public interface OrderConfirmedPublisher {
    // broker 확인까지 기다리고 실패하면 예외를 던진다(재발행 명령 경로).
    void republish(OrderConfirmedPayload payload);
}
