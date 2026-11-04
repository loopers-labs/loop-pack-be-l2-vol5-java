package com.loopers.outbox.application.port.out;

import com.loopers.outbox.domain.OrderOutbox;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 Outbox 저장소 (docs/week7/plan.md D-0·D-1). relay가 하나이고 회차가 겹치지 않으므로(D-1) 읽고 → 도메인 전이 → save로 쓴다.
 */
public interface OrderOutboxPort {

    OrderOutbox save(OrderOutbox outbox);

    Optional<OrderOutbox> findByEventId(String eventId);

    /**
     * relay 한 회차가 고를 행 (D-1): PENDING이거나 기한이 지난 SENDING이고 {@code next_attempt_at <= now}. id 순서로 최대 limit개.
     */
    List<OrderOutbox> findDue(ZonedDateTime now, int limit);
}
