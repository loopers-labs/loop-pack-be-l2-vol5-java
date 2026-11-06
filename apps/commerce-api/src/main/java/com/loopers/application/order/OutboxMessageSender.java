package com.loopers.application.order;

/**
 * 저장된 Outbox 행의 key·payload 를 그대로 broker 로 보내는 전송기 (7주차 설계 D27).
 * 객체를 받아 직렬화하는 W6 발행기(OrderConfirmedPublisher)와 달리 저장된 문자열만 보낸다. payload 를 다시 만들거나 상품을 조회하지 않는다.
 * 결과는 예외가 아니라 OutboxSendResult 로 돌려준다. 상태 변경은 relay 의 결과 TX 한 곳에서만 한다 (D25).
 */
public interface OutboxMessageSender {

    OutboxSendResult send(String messageKey, String payload);
}
