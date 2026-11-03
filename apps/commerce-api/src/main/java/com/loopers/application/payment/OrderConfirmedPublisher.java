package com.loopers.application.payment;

import com.loopers.domain.order.OrderConfirmedEvent;

/**
 * 주문 최초 확정 이벤트 발행기 (6주차 설계 D5). Facade 가 확정 TX 가 commit 된 뒤 호출한다.
 * 구현은 broker 저장 확인까지 기다리고, 실패하면 예외를 던진다. 호출자는 예외를 삼키고 ERROR 로그로 남긴다 (D7).
 * 테스트는 가짜 구현으로 "send 직전 중단"·"전송 실패"를 주입한다.
 */
public interface OrderConfirmedPublisher {

    void publish(OrderConfirmedEvent event);
}
