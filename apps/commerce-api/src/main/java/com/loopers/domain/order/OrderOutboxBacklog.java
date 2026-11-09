package com.loopers.domain.order;

import java.time.ZonedDateTime;

/**
 * 아직 broker 확인을 저장하지 못한 Outbox 행의 모습 (W7 design.md 4-1절). 발행 backlog와 오래 미발행된 행을 알려 준다.
 * 소비가 끝났는지는 알려 주지 않는다(PUBLISHED 뒤의 일은 consumer 쪽 기록으로 본다).
 *
 * @param pendingCount           PENDING 행 수
 * @param oldestPendingCreatedAt 가장 오래 기다린 PENDING 행의 기록 시각. 없으면 null
 * @param maxAttemptCount        PENDING 행 중 가장 많이 시도한 수. 없으면 0
 */
public record OrderOutboxBacklog(long pendingCount, ZonedDateTime oldestPendingCreatedAt, int maxAttemptCount) {
}
