package com.loopers.application.order;

/**
 * OrderConfirmed 를 broker record value(JSON 문자열)로 만든다. 확정 트랜잭션 안에서 Outbox 에 저장할 때 한 번 부른다.
 * 실패는 예외로 던진다. 확정 트랜잭션의 실패이므로 주문 확정까지 rollback 된다.
 */
public interface OrderConfirmedPayloadSerializer {

    String serialize(OrderConfirmedEvent event);
}
