package com.loopers.domain.order.gateway;

import com.loopers.domain.order.vo.OrderConfirmed;

/** 확정 사실을 다른 기능에 알림. 언제 부를지는 호출자가 정함(지금은 commit 뒤) */
public interface OrderEventPublisher {

    /** 실패해도 예외를 던지지 않고 기록만 함. 이미 확정된 주문을 되돌리지 않음 (6주차 D-73) */
    void publish(OrderConfirmed event);
}
