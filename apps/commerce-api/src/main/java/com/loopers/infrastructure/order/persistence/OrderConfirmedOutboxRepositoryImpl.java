package com.loopers.infrastructure.order.persistence;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.repository.OrderConfirmedOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxRepositoryImpl implements OrderConfirmedOutboxRepository {
    private final OrderConfirmedOutboxJpaRepository outboxJpaRepository;

    @Override
    public OrderConfirmedOutbox save(OrderConfirmedOutbox outbox) {
        return outboxJpaRepository.save(outbox);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findById(Long id) {
        return outboxJpaRepository.findById(id);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByOrderId(Long orderId) {
        return outboxJpaRepository.findByOrderId(orderId);
    }

    @Override
    public List<OrderConfirmedOutbox> findDue(ZonedDateTime now, int limit) {
        return outboxJpaRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OrderOutboxStatus.PENDING, now, Limit.of(limit));
    }
}
