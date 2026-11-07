package com.loopers.application.order;

import java.util.concurrent.CompletableFuture;

/**
 * 저장된 주문 이벤트를 broker 로 보낸다. relay 만 부른다. key·value 는 Outbox 에 저장된 문자열 그대로다.
 * 반환한 future 는 broker 가 기록을 확인하면 그 위치로, producer 재시도까지 실패하면 예외로 끝난다. send 호출이 예외 없이 반환한 것은 확인이 아니다.
 */
public interface OrderEventSender {

    CompletableFuture<Sent> send(String messageKey, String payload);

    /** broker 에 기록된 위치. topic + partition + offset 으로 한 record 를 특정한다. */
    record Sent(String topic, int partition, long offset) {
    }
}
