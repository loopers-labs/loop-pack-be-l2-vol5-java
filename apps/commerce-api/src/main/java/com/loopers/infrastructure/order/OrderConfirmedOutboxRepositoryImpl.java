package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxRepositoryImpl implements OrderConfirmedOutboxRepository {
    private final OrderConfirmedOutboxJpaRepository orderConfirmedOutboxJpaRepository;

    @Override
    public OrderConfirmedOutbox save(OrderConfirmedOutbox outbox) {
        return orderConfirmedOutboxJpaRepository.save(outbox);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByEventId(String eventId) {
        return orderConfirmedOutboxJpaRepository.findByEventId(eventId);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByOrderId(Long orderId) {
        return orderConfirmedOutboxJpaRepository.findByOrderId(orderId);
    }
}
