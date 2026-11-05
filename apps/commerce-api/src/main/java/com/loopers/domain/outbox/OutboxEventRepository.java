package com.loopers.domain.outbox;

import java.util.List;
import java.util.Optional;

/**
 * Outbox 행 저장소 포트(docs/week7/decisions.md D-12). 구현은 infrastructure/outbox의 JPA다.
 * 삭제 메서드는 두지 않는다 — 안 나간 행을 지우는 경로를 만들지 않는 것으로 INV-705를 지킨다(D-7, D-16).
 */
public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent event);

    Optional<OutboxEvent> findByEventId(String eventId);

    /** 한 주문의 행을 id 순서로 돌려준다. 확정 TX가 행을 정확히 하나 만들었는지 확인할 때 쓴다(INV-701). */
    List<OutboxEvent> findAllByOrderId(Long orderId);
}
