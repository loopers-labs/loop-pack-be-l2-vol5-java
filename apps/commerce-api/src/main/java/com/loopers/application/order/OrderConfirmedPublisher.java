package com.loopers.application.order;

/**
 * 주문 확정 이벤트를 밖으로 보내는 포트(docs/week6/decisions.md D-16). Kafka로 보내는 구현은 infrastructure에 있다 —
 * "commit 뒤에 발행을 시작한다"(트랜잭션 전략)와 "Kafka로 보낸다"(전송 수단)는 바뀌는 이유가 달라 떼어 둔다
 * (5주차 PaymentGateway와 같은 배치).
 *
 * <p>{@link OrderConfirmedPublication}의 발행 스레드에서만 불린다. 고객 요청 스레드에서 부르지 않는다(D-3, D-17).
 */
public interface OrderConfirmedPublisher {

    void publish(OrderConfirmedPayload payload);
}
