package com.loopers.application.outbox;

import java.time.ZonedDateTime;

/**
 * 집기(TX-1)가 끝난 행의 읽기 전용 사본(docs/week7/decisions.md D-2). relay는 TX 밖에서 이것만 들고 보낸다 —
 * 엔티티를 TX 밖으로 들고 나가지 않는다. messageKey·payload는 저장된 그대로다(INV-702).
 *
 * @param attemptCount 집으면서 1 늘린 뒤의 시도 횟수
 * @param createdAt    BaseEntity의 created_at(D-16). 24시간 판정의 기준이다.
 */
public record ClaimedOutbox(
    Long id,
    String eventId,
    Long orderId,
    String messageKey,
    String payload,
    int attemptCount,
    ZonedDateTime createdAt
) {}
