package com.loopers.outbox.domain;

/**
 * 밖으로 보낼 이벤트 한 건 (W6 ADR-W6-03). 업무 변경과 같은 트랜잭션에서 발행 기록으로 남긴다.
 *
 * @param eventId       이벤트 신원 — 같은 사실이면 몇 번을 보내도 같은 값 (ADR-W6-01)
 * @param aggregateType 이벤트를 낸 대상 종류 (예: ORDER)
 * @param aggregateId   대상 ID
 * @param eventType     이벤트 종류 (예: OrderConfirmed)
 * @param topic         보낼 topic
 * @param key           partition을 고르는 key — 같은 대상은 같은 key
 * @param payload       직렬화할 본문. 발행 기록을 남길 때 한 번만 JSON으로 바꾼다
 */
public record OutboxMessage(
    String eventId,
    String aggregateType,
    Long aggregateId,
    String eventType,
    String topic,
    String key,
    Object payload
) {}
