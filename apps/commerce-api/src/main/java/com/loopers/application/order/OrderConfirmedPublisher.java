package com.loopers.application.order;

import java.util.concurrent.CompletableFuture;

/**
 * 주문 확정 사실을 프로세스 밖(메시지 broker)으로 보낸다. 주문 확정 commit 뒤에 부른다 (OrderConfirmedEventListener).
 * 전송은 비동기다. 반환한 future 는 broker 가 기록을 확인하면 그 위치로, 재시도까지 실패하면 예외로 끝난다.
 * 실패해도 이미 확정된 주문은 바꾸지 않는다. commit 과 전송 사이에서 멈추면 이벤트가 빠진다 (DB 와 broker 는 원자적이지 않다).
 */
public interface OrderConfirmedPublisher {

    CompletableFuture<Published> publish(OrderConfirmedEvent event);

    /** broker 에 기록된 위치. topic + partition + offset 으로 한 record 를 특정한다. */
    record Published(String topic, int partition, long offset) {
    }
}
