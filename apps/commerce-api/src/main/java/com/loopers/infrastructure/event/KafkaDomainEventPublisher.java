package com.loopers.infrastructure.event;

import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One application send per fact; Kafka producer retries never retry the originating business operation. */
@Slf4j
public class KafkaDomainEventPublisher implements DomainEventPublisher {
    private final KafkaTemplate<String, DomainEvent> kafka;
    private final Map<Class<? extends DomainEvent>, KafkaEventRoute> routes;

    public KafkaDomainEventPublisher(KafkaTemplate<String, DomainEvent> kafka, List<KafkaEventRoute> routes) {
        this.kafka = kafka;
        Map<Class<? extends DomainEvent>, KafkaEventRoute> indexed = new HashMap<>();
        for (KafkaEventRoute route : routes) {
            if (indexed.putIfAbsent(route.eventClass(), route) != null) {
                throw new IllegalArgumentException("Only one route per event class is allowed");
            }
        }
        this.routes = Map.copyOf(indexed);
    }

    @Override
    public void publish(DomainEvent event) {
        KafkaEventRoute route = routes.get(event.getClass());
        String topic = route == null ? "<unregistered>" : route.topic();
        try {
            if (route == null) {
                throw new IllegalArgumentException("No external publication route is registered for this event");
            }
            if (!route.enabled()) {
                log.warn("domain_event_publication_disabled eventType={} eventId={} key={} topic={}",
                    event.eventType(), event.eventId(), event.key(), topic);
                return;
            }
            // No get/join/flush: preserve the committed response without waiting for broker acknowledgement.
            kafka.send(topic, event.key(), event).whenComplete((result, failure) -> {
                if (failure != null) {
                    failed(event, topic, failure);
                    return;
                }
                var metadata = result.getRecordMetadata();
                log.info("domain_event_published eventType={} eventId={} key={} topic={} partition={} offset={}",
                    event.eventType(), event.eventId(), event.key(), metadata.topic(), metadata.partition(), metadata.offset());
            });
        } catch (RuntimeException failure) {
            // Includes synchronous metadata/buffer/serialization failure. Failure alone does not prove absence.
            failed(event, topic, failure);
        }
    }

    private void failed(DomainEvent event, String topic, Throwable failure) {
        // Whitelisted identifiers only: no raw exception message, payload or payment material.
        log.error("domain_event_publication_failed eventType={} eventId={} key={} topic={} errorType={}",
            event.eventType(), event.eventId(), event.key(), topic, failure.getClass().getSimpleName());
    }
}
