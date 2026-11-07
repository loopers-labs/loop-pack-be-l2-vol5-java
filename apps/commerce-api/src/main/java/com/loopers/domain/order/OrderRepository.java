package com.loopers.domain.order;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    OrderModel save(OrderModel order);

    Optional<OrderModel> find(Long id);

    /** 결제 시작·결과 반영용: 비관적 쓰기 락(SELECT ... FOR UPDATE). 주문 행을 다른 락보다 먼저 잡는다 (주문 → 재고 → 상품). */
    Optional<OrderModel> findForUpdate(Long id);

    /**
     * 결제 대기 만료용: startedBefore 이전에 결제를 시작한 AWAITING_PAYMENT 주문 하나를 결제 시작 순으로 잠근다 (FOR UPDATE SKIP LOCKED).
     * 다른 트랜잭션이 잠근 주문은 기다리지 않고 건너뛴다.
     */
    Optional<OrderModel> findExpiredAwaitingForUpdate(ZonedDateTime startedBefore);

    /** TB-14 도 이 Repository 가 맡는다 (포함 개념, DR-36·38 과 같은 이유). 저장은 즉시 insert 한다: event_id 중복은 확정 트랜잭션 안에서 드러난다. */
    OrderEventOutboxModel saveOutbox(OrderEventOutboxModel outbox);

    Optional<OrderEventOutboxModel> findOutboxByEventId(String eventId);

    /** relay 상태 변경용: Outbox 행 쓰기 락 (SELECT ... FOR UPDATE). 재개와 relay 가 같은 행을 동시에 바꾸지 않게. */
    Optional<OrderEventOutboxModel> findOutboxByEventIdForUpdate(String eventId);

    /** relay 대상: now 까지 재시도 시각이 된 PENDING 을 (next_attempt_at, id) 순으로 limit 건 (IX-15). BLOCKED·PUBLISHED 는 고르지 않는다. */
    List<OrderEventOutboxModel> findDueOutbox(ZonedDateTime now, int limit);
}
