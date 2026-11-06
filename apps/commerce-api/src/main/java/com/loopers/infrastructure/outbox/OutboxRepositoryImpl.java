package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OutboxRepositoryImpl implements OutboxRepository {
    private final OutboxJpaRepository repository;

    public OutboxRepositoryImpl(OutboxJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public OutboxMessage save(OutboxMessage message) {
        return repository.saveAndFlush(message);
    }

    @Override
    public Optional<OutboxMessage> findByEventId(String eventId) {
        return repository.findByEventId(eventId);
    }

    @Override
    public List<Long> findDueIds(Instant now, int limit) {
        if (now == null || limit < 1) {
            throw new IllegalArgumentException("Outbox due lookup requires a time and positive limit");
        }
        return repository.findDueIds(OutboxStatus.PENDING, now, PageRequest.of(0, limit));
    }

    @Override
    public Optional<OutboxMessage> findByIdForUpdate(Long id) {
        return repository.findByIdForUpdate(id);
    }
}
