package com.loopers.domain.outbox;

import com.loopers.domain.event.DomainEvent;

public interface OutboxMessageSerializer {
    OutboxMessage serialize(DomainEvent event);
}
