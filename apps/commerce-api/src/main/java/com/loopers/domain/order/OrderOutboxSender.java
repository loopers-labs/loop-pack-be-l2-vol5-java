package com.loopers.domain.order;

/**
 * Outbox 한 건을 broker로 보내고 broker 확인을 기다리는 경계. Kafka·직렬화·timeout은 infrastructure 구현만 안다.
 *
 * <p>구현은 예외를 던지지 않고 결과를 돌려준다. {@link OrderEventPublication.Sent}는 broker가 알려 준 위치이고,
 * {@link OrderEventPublication.Failed}는 broker가 받지 않았다는 뜻이 아니라 "받았음을 확인하지 못했다"는 뜻이다(timeout 뒤에 쓰였을 수 있다).
 */
public interface OrderOutboxSender {

    OrderEventPublication send(OrderOutboxMessage message);
}
