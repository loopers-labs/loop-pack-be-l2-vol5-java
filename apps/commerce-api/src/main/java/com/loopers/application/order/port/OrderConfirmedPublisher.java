package com.loopers.application.order.port;

import com.loopers.application.order.OrderConfirmedPayload;

public interface OrderConfirmedPublisher {
    // 결과를 기다리지 않는다. 실패는 로그·지표로만 드러낸다(사용자 요청 경로).
    void send(OrderConfirmedPayload payload);

    // broker 확인까지 기다리고 실패하면 예외를 던진다(재발행 명령 경로).
    void republish(OrderConfirmedPayload payload);
}
