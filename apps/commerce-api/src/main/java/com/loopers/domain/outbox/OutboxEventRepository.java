package com.loopers.domain.outbox;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Outbox 행 저장소 포트(docs/week7/decisions.md D-12). 구현은 infrastructure/outbox의 JPA다.
 * 삭제 메서드는 두지 않는다 — 안 나간 행을 지우는 경로를 만들지 않는 것으로 INV-705를 지킨다(D-7, D-16).
 */
public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent event);

    Optional<OutboxEvent> findById(Long id);

    Optional<OutboxEvent> findByEventId(String eventId);

    /** 한 주문의 행을 id 순서로 돌려준다. 확정 TX가 행을 정확히 하나 만들었는지 확인할 때 쓴다(INV-701). */
    List<OutboxEvent> findAllByOrderId(Long orderId);

    /**
     * 보낼 차례가 된 PENDING 행(next_attempt_at &lt;= now)의 id를 id 순서로 최대 limit개 돌려준다(D-15 — 작은 묶음, 안정된 순서).
     * 고르기만 한다. 집는 것은 {@link #claim}이 따로 한다 — 고른 뒤 다른 회차가 먼저 집었을 수 있다.
     *
     * @param now 주입한 Clock의 "지금"(D-11). DB NOW()는 쓰지 않는다.
     */
    List<Long> findDueIds(ZonedDateTime now, int limit);

    /**
     * 한 행을 조건부 UPDATE 한 문장으로 집는다(D-4, INV-704): PENDING이고 next_attempt_at &lt;= now일 때만
     * attempt_count + 1, next_attempt_at = leaseUntil. 겹침 판정을 DB 한 곳에서 한다 — 서버가 달라도 메모리를 공유하지 않는다.
     *
     * @return 바뀐 행 수. 0이면 다른 회차가 이미 집었거나 상태가 바뀐 것이니 건너뛴다.
     */
    int claim(Long id, ZonedDateTime now, ZonedDateTime leaseUntil);

    /** 관찰용: 상태별 행 수. relay 회차 로그가 PENDING·BLOCKED 수를 남긴다(6주차 미발행 Counter를 대신한다, D-9). */
    long countByStatus(OutboxStatus status);

    /** 관찰용: 그 상태 중 가장 오래된 created_at. 가장 오래 기다린 행이 얼마나 밀렸는지를 본다. 없으면 빈 값이다. */
    Optional<ZonedDateTime> findOldestCreatedAt(OutboxStatus status);
}
