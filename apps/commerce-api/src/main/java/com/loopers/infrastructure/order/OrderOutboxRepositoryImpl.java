package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxRepository;
import com.loopers.domain.order.OrderOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderOutboxRepositoryImpl implements OrderOutboxRepository {
    private final OrderOutboxJpaRepository outbox;

    @Override
    public List<OrderOutbox> findDue(Instant now, int limit) {
        return outbox.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            OrderOutboxStatus.PENDING, now, PageRequest.of(0, limit));
    }

    @Override
    public Optional<OrderOutbox> findById(Long id) {
        return outbox.findById(id);
    }

    @Override
    public Optional<OrderOutbox> findByEventId(String eventId) {
        return outbox.findByEventId(eventId);
    }
}
