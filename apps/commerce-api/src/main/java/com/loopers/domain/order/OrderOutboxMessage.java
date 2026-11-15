package com.loopers.domain.order;

/**
 * relay가 broker로 보낼 한 건. Outbox 행에 저장된 key·payload를 그대로 담고, 현재 상품·주문을 다시 읽지 않는다.
 *
 * @param attempt 이번 시도의 번호(시도를 시작한 뒤의 {@code attemptCount}). 실패를 기록할 때 같은 시도인지 확인하는 데 쓴다
 */
public record OrderOutboxMessage(String eventId, Long orderId, String messageKey, String payload, int attempt) {

    public static OrderOutboxMessage of(OrderOutboxModel outbox) {
        return new OrderOutboxMessage(
            outbox.getEventId(), outbox.getOrderId(), outbox.getMessageKey(), outbox.getPayload(), outbox.getAttemptCount()
        );
    }
}
