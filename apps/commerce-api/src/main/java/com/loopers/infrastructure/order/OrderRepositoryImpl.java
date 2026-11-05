package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEventOutboxModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;
    private final OrderEventOutboxJpaRepository orderEventOutboxJpaRepository;

    @Override
    public OrderModel save(OrderModel order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<OrderModel> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return orderJpaRepository.findById(id);
    }

    @Override
    public Optional<OrderModel> findForUpdate(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return orderJpaRepository.findByIdForUpdate(id);
    }

    @Override
    public Optional<OrderModel> findExpiredAwaitingForUpdate(ZonedDateTime startedBefore) {
        return orderJpaRepository.findFirstByStatusAndPaymentStartedAtLessThanEqualOrderByPaymentStartedAtAsc(
            OrderStatus.AWAITING_PAYMENT, startedBefore);
    }

    @Override
    public OrderEventOutboxModel saveOutbox(OrderEventOutboxModel outbox) {
        return orderEventOutboxJpaRepository.saveAndFlush(outbox);
    }

    @Override
    public Optional<OrderEventOutboxModel> findOutboxByEventId(String eventId) {
        return orderEventOutboxJpaRepository.findByEventId(eventId);
    }

    @Override
    public Optional<OrderEventOutboxModel> findOutboxByEventIdForUpdate(String eventId) {
        return orderEventOutboxJpaRepository.findByEventIdForUpdate(eventId);
    }

    @Override
    public List<OrderEventOutboxModel> findDueOutbox(ZonedDateTime now, int limit) {
        return orderEventOutboxJpaRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
            OutboxStatus.PENDING, now, PageRequest.ofSize(limit));
    }
}
