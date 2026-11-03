package com.loopers.domains.order.app.port;

// relay가 보낼 Outbox 행 하나. key·payload는 저장된 그대로 보낸다(D112).
public record OutboxMessage(long id, String key, String payload, int attemptCount, int rowFailureCount) {
}
