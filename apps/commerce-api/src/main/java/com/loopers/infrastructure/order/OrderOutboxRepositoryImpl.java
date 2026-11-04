package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderOutboxRepositoryImpl implements OrderOutboxRepository {
    private final OrderOutboxJpaRepository orderOutboxJpaRepository;

    @Override
    public OrderOutboxModel save(OrderOutboxModel outbox) {
        return orderOutboxJpaRepository.save(outbox);
    }

    @Override
    public Optional<OrderOutboxModel> findByEventId(String eventId) {
        return orderOutboxJpaRepository.findByEventId(eventId);
    }
}
