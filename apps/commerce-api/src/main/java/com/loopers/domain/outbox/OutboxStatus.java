package com.loopers.domain.outbox;

/**
 * Outbox 행의 상태(docs/week7/decisions.md D-2, D-3). 안 나간 행(PENDING·BLOCKED)은 지우지 않는다(INV-705).
 */
public enum OutboxStatus {
    /** 아직 broker가 받았다는 응답이 없다. relay가 next_attempt_at이 되면 집는다. */
    PENDING,
    /** broker가 받았다고 응답한 뒤에만 기록한다(INV-703). 소비됐다는 뜻은 아니다(D-7). */
    PUBLISHED,
    /** 24시간 동안 실패해 relay가 더 집지 않는다. DLQ가 아니라 "해결되지 않음"이다(D-3). 재개는 같은 행을 PENDING으로 되돌린다. */
    BLOCKED
}
