package com.loopers.application.order;

/**
 * Outbox에 저장된 주문 확정 이벤트를 밖으로 보내는 포트(docs/week7/decisions.md D-9). Kafka로 보내는 구현은
 * infrastructure에 있다 — "언제·무엇을 다시 보낼지"(relay)와 "Kafka로 보낸다"(전송 수단)는 바뀌는 이유가 달라 떼어 둔다
 * (6주차 D-16, 5주차 PaymentGateway와 같은 배치).
 *
 * <p>6주차의 {@code publish(payload 객체) → void}에서 바뀌었다. relay는 결과를 보고 PUBLISHED로 바꿀지 last_error를
 * 남길지 정해야 하므로 결과를 돌려받는다. 보낼 내용은 다시 만들지 않고 저장된 문자열을 그대로 받는다(INV-702).
 */
public interface OrderConfirmedPublisher {

    /**
     * key와 value를 바꾸지 않고 보내고, broker의 응답이 올 때까지 기다린다. 이 메서드는 따로 대기 상한을 두지 않는다 —
     * producer의 delivery.timeout.ms·max.block.ms가 상한이다. relay가 producer보다 먼저 포기하면 producer 안의 전송이
     * 살아 있는 채로 실패로 기록되고 임대가 일찍 풀린다(D-2).
     *
     * @param messageKey Outbox 행의 message_key
     * @param payload    Outbox 행의 payload(계약 JSON 문자열)
     * @return broker가 받은 위치
     * @throws OrderConfirmedSendException broker가 받았다고 응답하지 않았을 때(실패·타임아웃·인터럽트)
     */
    SentRecord send(String messageKey, String payload);
}
