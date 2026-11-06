package com.loopers.outbox.application.port.in;

import com.loopers.outbox.domain.OrderOutboxStatus;

/**
 * 사람이 원인을 고친 뒤 BLOCKED 행을 다시 보낼 차례로 돌린다 (docs/week7/plan.md D-3). 보내지는 않는다 — 다음 relay tick이 보낸다.
 * relay를 직접 돌리지 않으므로, relay를 실행하는 길은 scheduler 하나뿐이고 그 길을 ShedLock이 지킨다 (겹침 방지).
 */
public interface OrderOutboxResumeUseCase {

    /**
     * @throws com.loopers.support.error.CoreException NOT_FOUND(행 없음) · CONFLICT(BLOCKED가 아님)
     */
    ResumeResult resume(String eventId);

    record ResumeResult(String eventId, Long orderId, OrderOutboxStatus status, int attemptCount, String lastError) {}
}
