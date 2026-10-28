package com.loopers.outbox.domain;

/**
 * 발행 기록이 생겼다는 프로세스 안 이벤트 (W6 ADR-W6-02). 트랜잭션 안에서 내고, commit된 뒤에만 Kafka로 보낸다.
 * payload는 기록에 저장한 JSON 문자열 그대로다 — 저장한 것과 보내는 것의 출처가 같다.
 */
public record OutboxAppendedEvent(String eventId, String topic, String key, String payload) {}
