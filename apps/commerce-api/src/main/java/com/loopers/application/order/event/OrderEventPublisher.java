package com.loopers.application.order.event;

import com.loopers.application.order.dto.OrderConfirmedInfo;

/**
 * 주문 확정 사실을 다른 시스템에 알리는 포트. 이를 쓰는 application 이 소유하고 infrastructure 가 구현한다.
 *
 * <p>호출 시점에 주문 확정은 이미 commit 되었으므로 구현은 전송 오류를 호출자에게 던지지 않는다. 오류는 로그로 드러내고,
 * 호출자는 확정된 주문을 되돌리거나 결제를 다시 요청하지 않는다.
 */
public interface OrderEventPublisher {

    void publish(OrderConfirmedInfo event);
}
