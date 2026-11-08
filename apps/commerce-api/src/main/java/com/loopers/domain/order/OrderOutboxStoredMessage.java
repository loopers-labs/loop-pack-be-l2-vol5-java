package com.loopers.domain.order;

/**
 * Outbox 행에 저장된 전송 사실의 raw scalar 복사본(설계 W7.3-2). 손상된 본문·메타데이터도 verifier 가 검사할 수 있도록
 * 생성 시 해석·검사·보정을 하지 않는다. 신규 기록용으로 사실을 검사하는 {@link OrderOutboxMessage} 와 역할이 다르다.
 * Entity·JSON·Kafka 객체를 담지 않는다. 로그에 원문이 섞이지 않도록 toString 은 key·payload 를 출력하지 않는다.
 */
public record OrderOutboxStoredMessage(String eventId, Long orderId, String eventType, Integer schemaVersion, String messageKey,
                                       String payload) {

    @Override
    public String toString() {
        return "OrderOutboxStoredMessage[orderId=" + orderId + ", schemaVersion=" + schemaVersion + "]";
    }
}
