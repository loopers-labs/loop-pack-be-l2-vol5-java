package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.loopers.application.order.OrderConfirmedOutbox;
import com.loopers.application.order.OrderOutbox;
import com.loopers.application.order.OrderOutboxMessage;
import com.loopers.kafka.event.OrderConfirmed;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedOutboxWriter implements OrderConfirmedOutbox {
    private final OrderOutbox outbox;
    private final ObjectMapper objectMapper;
    private final String destinationTopic;

    public OrderConfirmedOutboxWriter(OrderOutbox outbox, ObjectMapper objectMapper,
            @Value("${order-events.topic:order-events}") String destinationTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.destinationTopic = destinationTopic;
    }

    @Override
    public void record(OrderConfirmed event) {
        outbox.record(toMessage(event));
    }

    @Override
    public boolean recordIfAbsent(OrderConfirmed event) {
        return outbox.recordIfAbsent(toMessage(event));
    }

    private OrderOutboxMessage toMessage(OrderConfirmed event) {
        try {
            return new OrderOutboxMessage(event.eventId(), event.orderId(), event.eventType(), event.schemaVersion(),
                    Long.toString(event.orderId()), destinationTopic, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize order confirmation for outbox", exception);
        }
    }
}
