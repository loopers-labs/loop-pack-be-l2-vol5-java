package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository outboxEventJpaRepository;

    @Override
    public OutboxEvent save(OutboxEvent event) {
        return outboxEventJpaRepository.save(event);
    }

    @Override
    public Optional<OutboxEvent> findById(Long id) {
        return outboxEventJpaRepository.findById(id);
    }

    @Override
    public Optional<OutboxEvent> findByEventId(String eventId) {
        return outboxEventJpaRepository.findByEventId(eventId);
    }

    @Override
    public List<OutboxEvent> findAllByOrderId(Long orderId) {
        return outboxEventJpaRepository.findAllByOrderIdOrderByIdAsc(orderId);
    }

    @Override
    public List<Long> findDueIds(ZonedDateTime now, int limit) {
        return outboxEventJpaRepository.findDueIds(OutboxStatus.PENDING, now, Limit.of(limit));
    }

    @Override
    public int claim(Long id, ZonedDateTime now, ZonedDateTime leaseUntil) {
        return outboxEventJpaRepository.claim(id, OutboxStatus.PENDING, now, leaseUntil);
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return outboxEventJpaRepository.countByStatus(status);
    }

    @Override
    public Optional<ZonedDateTime> findOldestCreatedAt(OutboxStatus status) {
        return Optional.ofNullable(outboxEventJpaRepository.findOldestCreatedAt(status));
    }
}
