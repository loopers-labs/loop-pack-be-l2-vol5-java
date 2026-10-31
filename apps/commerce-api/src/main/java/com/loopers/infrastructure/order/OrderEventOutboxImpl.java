package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEvent;
import com.loopers.domain.order.OrderEventOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
class OrderEventOutboxImpl implements OrderEventOutbox {

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
        switch (event) {
            case OrderConfirmed confirmed -> {
                OrderConfirmedMessage message = OrderConfirmedMessage.from(confirmed);
                outboxJpaRepository.save(OrderEventOutboxEntity.pending(message, topic, serialize(message)));
            }
        }
    }

    private byte[] serialize(OrderConfirmedMessage message) {
        try {
            return message.toBytes(objectMapper);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 이벤트를 직렬화하지 못했다: eventId=" + message.eventId(), e);
        }
    }
}
