package com.loopers.fixture.pg;

import java.time.Instant;

/**
 * PG endpoint 수신 1건. 카드·요청 원문은 남기지 않는다.
 * target 은 검증 전에 거절되면 null 이고, completedAt 은 처리 중이면 null 이다.
 * responseStatus 는 보내려 한 상태 코드이며 응답 없이 닫은 경우 null 이다.
 */
public record JournalEntry(
    long sequence,
    PgOperation operation,
    String target,
    Instant receivedAt,
    Instant completedAt,
    Integer responseStatus,
    String createdTransactionKey,
    ResponseFailure failure
) {
}
