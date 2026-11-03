package com.loopers.infrastructure.order.persistence;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.repository.OrderConfirmedOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    public Optional<OrderConfirmedOutbox> findByOrderId(Long orderId) {
        return outboxJpaRepository.findByOrderId(orderId);
    }
}
