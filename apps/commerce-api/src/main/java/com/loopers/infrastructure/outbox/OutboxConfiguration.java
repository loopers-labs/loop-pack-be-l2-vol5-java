package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.outbox.OutboxMessageMatcher;
import com.loopers.domain.outbox.OutboxMessageSerializer;
import com.loopers.infrastructure.event.KafkaEventRoute;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {
    @Bean
    public Clock outboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OutboxEventMapping<OrderConfirmedEvent> orderConfirmedOutboxMapping(
            @Qualifier("orderConfirmedEventRoute") KafkaEventRoute route) {
        // Disabling transport does not disable the transactional recording of facts.
        return new OutboxEventMapping<>(OrderConfirmedEvent.class, "Order", OrderConfirmedEvent::schemaVersion,
            event -> Long.toString(event.orderId()), route.topic());
    }

    @Bean
    public OutboxMessageSerializer outboxMessageSerializer(ObjectMapper mapper,
            @Qualifier("outboxClock") Clock clock, List<OutboxEventMapping<?>> mappings) {
        return new JsonOutboxMessageSerializer(mapper, clock, mappings);
    }

    @Bean
    public OutboxMessageMatcher outboxMessageMatcher(ObjectMapper mapper) {
        return new JsonOutboxMessageMatcher(mapper);
    }
}
