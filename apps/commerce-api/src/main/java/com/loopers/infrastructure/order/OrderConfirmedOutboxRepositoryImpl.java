package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderConfirmedOutboxRepositoryImpl implements OrderConfirmedOutboxRepository {

    private final OrderConfirmedOutboxJpaRepository jpa;

    @Override
    public OrderConfirmedOutbox save(OrderConfirmedOutbox outbox) {
        return jpa.save(outbox);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findById(Long id) {
        return jpa.findById(id);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByEventId(String eventId) {
        return jpa.findByEventId(eventId);
    }

    @Override
    public List<OrderConfirmedOutbox> findPendingToProcess(ZonedDateTime before, int limit) {
        return jpa.findPendingToProcess(OutboxStatus.PENDING, before, limit);
    }
}
