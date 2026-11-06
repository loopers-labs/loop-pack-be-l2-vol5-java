package com.loopers.domain.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository {
    /** Insert participates in the caller's order confirmation transaction; errors must propagate. */
    OutboxMessage save(OutboxMessage message);

    Optional<OutboxMessage> findByEventId(String eventId);

    List<Long> findDueIds(Instant now, int limit);

    Optional<OutboxMessage> findByIdForUpdate(Long id);
}
