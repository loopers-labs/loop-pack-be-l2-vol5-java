package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    public Optional<OutboxEvent> findByEventId(String eventId) {
        return outboxEventJpaRepository.findByEventId(eventId);
    }

    @Override
    public List<OutboxEvent> findAllByOrderId(Long orderId) {
        return outboxEventJpaRepository.findAllByOrderIdOrderByIdAsc(orderId);
    }
}
