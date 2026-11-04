package com.loopers.domain.outbox;

import java.time.ZonedDateTime;
import java.util.List;

public interface OutboxRepository {
    Outbox save(Outbox outbox);

    /** PENDING 이고 다음 시도 시각이 {@code now} 이하인 건을 기록한 순서대로 최대 {@code limit} 개 가져온다. */
    List<Outbox> findDueForDelivery(ZonedDateTime now, int limit);
}
