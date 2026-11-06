package com.loopers.application.event;

import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.outbox.OutboxMessageSerializer;
import com.loopers.domain.outbox.OutboxRecorder;
import com.loopers.domain.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionalOutboxRecorder implements OutboxRecorder {
    private final OutboxMessageSerializer serializer;
    private final OutboxRepository repository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(DomainEvent event) {
        repository.save(serializer.serialize(event));
    }
}
