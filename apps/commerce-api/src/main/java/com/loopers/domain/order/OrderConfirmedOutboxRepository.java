package com.loopers.domain.order;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 확정 Outbox 저장소 (7주차 설계 D11·D23·D25·D29).
 * 확정 TX 의 insert 와 조회, 그리고 relay 가 쓰는 묶음 조회·조건부 상태 UPDATE 를 둔다.
 * 상태를 바꾸는 메서드는 모두 현재 상태를 WHERE 조건으로 걸어, 늦은 결과가 이미 끝난 행을 덮어쓰지 못하게 한다.
 */
public interface OrderConfirmedOutboxRepository {

    OrderConfirmedOutbox save(OrderConfirmedOutbox outbox);

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);

    Optional<OrderConfirmedOutbox> findByOrderId(Long orderId);

    /**
     * relay 회차 ①. 재시도 시간이 된 PENDING 을 next_attempt_at, id 순으로 limit 만큼 읽는다 (읽기만).
     */
    List<OrderConfirmedOutbox> findPendingDue(ZonedDateTime now, int limit);

    /**
     * relay 회차 ②. 시도 시작 기록: attempt_count + 1. WHERE status = 'PENDING'.
     *
     * @return 갱신된 행 수. 0 이면 이미 PENDING 이 아닌 행이다
     */
    int startAttempt(Long id);

    /**
     * relay 회차 ④ 성공. PENDING → PUBLISHED, published_at 기록. WHERE status = 'PENDING'.
     */
    int markPublished(Long id, ZonedDateTime publishedAt);

    /**
     * relay 회차 ④ 일시 실패·timeout. PENDING 유지, next_attempt_at·last_error 기록. WHERE status = 'PENDING'.
     */
    int scheduleRetry(Long id, ZonedDateTime nextAttemptAt, String lastError);

    /**
     * relay 회차 ④ 영구 오류. PENDING → BLOCKED, last_error 기록. WHERE status = 'PENDING'.
     */
    int markBlocked(Long id, String lastError);

    /**
     * 재개. BLOCKED → PENDING, next_attempt_at = now. WHERE event_id = ? AND status = 'BLOCKED'.
     *
     * @return 갱신된 행 수. 0 이면 없는 eventId 거나 BLOCKED 가 아닌 행이다
     */
    int resumeBlocked(String eventId, ZonedDateTime now);

    /** 관찰용. 지금 PENDING 인 행 수 (D31). */
    long countPending();
}
