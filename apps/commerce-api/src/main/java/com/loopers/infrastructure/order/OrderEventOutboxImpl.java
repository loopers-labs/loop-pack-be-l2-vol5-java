package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.PendingOrderEvent;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Repository
class OrderEventOutboxImpl implements OrderEventOutbox {

    private static final int ERROR_LENGTH = 1000;

    private final OrderEventOutboxJpaRepository outboxJpaRepository;
    private final ObjectMapper objectMapper;
    private final String topic;

    OrderEventOutboxImpl(OrderEventOutboxJpaRepository outboxJpaRepository, ObjectMapper objectMapper,
                         @Value("${kafka.topics.order-events}") String topic) {
        this.outboxJpaRepository = outboxJpaRepository;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void append(OrderEvent event) {
        outboxJpaRepository.save(pending(event));
    }

    @Override
    public boolean appendIfAbsent(OrderEvent event) {
        OrderEventOutboxEntity candidate = pending(event);
        Optional<OrderEventOutboxEntity> stored = outboxJpaRepository.findByEventId(candidate.eventId());
        if (stored.isEmpty()) {
            outboxJpaRepository.save(candidate);
            return true;
        }
        if (!stored.get().sameRecordAs(candidate)) {
            throw new DomainException(DomainError.ORDER_EVENT_CONFLICT);
        }
        return false;
    }

    private byte[] serialize(OrderConfirmedMessage message) {
        try {
            return message.toBytes(objectMapper);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 이벤트를 직렬화하지 못했다: eventId=" + message.eventId(), e);
        }
    }

    @Override
    public List<PendingOrderEvent> due(int limit) {
        return outboxJpaRepository.findDue(limit).stream().map(OrderEventOutboxEntity::toPending).toList();
    }

    @Override
    public void markPublished(List<Long> ids) {
        if (!ids.isEmpty()) {
            outboxJpaRepository.markPublished(ids);
        }
    }

    @Override
    public void markBlocked(Long id, String error) {
        outboxJpaRepository.markBlocked(id, truncate(error));
    }

    @Override
    public void retryLater(Long id, Duration delay, Duration giveUpAfter, String error) {
        outboxJpaRepository.retryLater(id, delay.toNanos() / 1_000, giveUpAfter.toNanos() / 1_000, truncate(error));
    }

    @Override
    public boolean resume(String eventId) {
        return outboxJpaRepository.resume(eventId) == 1;
    }

    private static String truncate(String error) {
        return error.length() <= ERROR_LENGTH ? error : error.substring(0, ERROR_LENGTH);
    }

    private OrderEventOutboxEntity pending(OrderEvent event) {
        return switch (event) {
            case OrderConfirmed confirmed -> {
                OrderConfirmedMessage message = OrderConfirmedMessage.from(confirmed);
                yield OrderEventOutboxEntity.pending(message, topic, serialize(message));
            }
        };
    }
}
