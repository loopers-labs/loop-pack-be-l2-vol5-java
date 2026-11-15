package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderOutboxBacklog;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxRepository;
import com.loopers.domain.order.OrderOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
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

    @Override
    public List<String> findDuePendingEventIds(Instant now, int limit) {
        return orderOutboxJpaRepository.findDueEventIds(OrderOutboxStatus.PENDING, now, Limit.of(limit));
    }

    @Override
    public OrderOutboxBacklog pendingBacklog() {
        OrderOutboxJpaRepository.StatusSummary summary = orderOutboxJpaRepository.summarize(OrderOutboxStatus.PENDING);
        Integer maxAttemptCount = summary.getMaxAttemptCount();
        return new OrderOutboxBacklog(summary.getRowCount(), summary.getOldestCreatedAt(), maxAttemptCount == null ? 0 : maxAttemptCount);
    }
}
