package com.loopers.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the configured serializers and route beans without creating a Kafka producer or a DB. */
class DomainEventKafkaConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(DomainEventKafkaConfiguration.class)
        .withBean(ObjectMapper.class, () -> JsonMapper.builder().addModule(new JavaTimeModule()).build())
        .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1");

    @Test
    void commonSerializerPreservesTheExactOrderConfirmationWireContractAndStringKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            DefaultKafkaProducerFactory<String, DomainEvent> factory = factory(context);
            DomainEvent event = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42", 42L,
                Instant.parse("2026-10-29T00:00:00.123456Z"), List.of(new OrderConfirmedEvent.Item(7L, 2)));
            RecordHeaders headers = new RecordHeaders();

            byte[] key = factory.getKeySerializer().serialize("order-events", event.key());
            byte[] payload = factory.getValueSerializer().serialize("order-events", headers, event);

            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThat(key).isEqualTo("42".getBytes(StandardCharsets.UTF_8));
            assertThat(mapper.readTree(payload)).isEqualTo(mapper.readTree("""
                {
                  "eventType": "OrderConfirmed",
                  "schemaVersion": 1,
                  "eventId": "order-confirmed:v1:42",
                  "orderId": 42,
                  "confirmedAt": "2026-10-29T00:00:00.123456Z",
                  "items": [{"productId": 7, "quantity": 2}]
                }
                """));
            assertThat(headers).isEmpty();
        });
    }

    @Test
    void commonSerializerRetainsASecondEventsOwnFieldsWithoutAnEnvelopeOrTypeHeaders() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            DefaultKafkaProducerFactory<String, DomainEvent> factory = factory(context);
            DomainEvent event = new StockChangedEvent("StockChanged", 1, "stock-changed:test:7", 7L,
                Instant.parse("2026-10-29T01:02:03.456789Z"), "test-only adjustment");
            RecordHeaders headers = new RecordHeaders();

            byte[] key = factory.getKeySerializer().serialize("stock-events-test", event.key());
            byte[] payload = factory.getValueSerializer().serialize("stock-events-test", headers, event);

            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThat(key).isEqualTo("product:7".getBytes(StandardCharsets.UTF_8));
            assertThat(mapper.readTree(payload)).isEqualTo(mapper.readTree("""
                {
                  "eventType": "StockChanged",
                  "schemaVersion": 1,
                  "eventId": "stock-changed:test:7",
                  "productId": 7,
                  "changedAt": "2026-10-29T01:02:03.456789Z",
                  "reason": "test-only adjustment"
                }
                """));
            assertThat(headers).isEmpty();
        });
    }

    @Test
    void defaultOrderRouteUsesTheExistingTopicAndTheCommonPublisherAndTemplate() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DomainEventPublisher.class)
                .hasSingleBean(KafkaTemplate.class).hasSingleBean(KafkaEventRoute.class);
            assertThat(context.getBean(KafkaEventRoute.class))
                .isEqualTo(new KafkaEventRoute(OrderConfirmedEvent.class, "order-events", true));
            assertThat(context.getBean(DomainEventPublisher.class)).isInstanceOf(KafkaDomainEventPublisher.class);
            assertThat(context.getBean(KafkaTemplate.class).getProducerFactory()).isSameAs(factory(context));
        });
    }

    @Test
    void configuredOrderTopicAndDisabledFlagArePreservedInTheRoute() {
        runner.withPropertyValues("commerce.order-events.topic=owned-order-events-test",
                "commerce.order-events.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(DomainEventPublisher.class)
                    .hasSingleBean(KafkaEventRoute.class);
                assertThat(context.getBean(KafkaEventRoute.class))
                    .isEqualTo(new KafkaEventRoute(OrderConfirmedEvent.class, "owned-order-events-test", false));
            });
    }

    @SuppressWarnings("unchecked")
    private static DefaultKafkaProducerFactory<String, DomainEvent> factory(AssertableApplicationContext context) {
        return context.getBean("domainEventProducerFactory", DefaultKafkaProducerFactory.class);
    }

    private record StockChangedEvent(String eventType, int schemaVersion, String eventId, long productId,
                                     Instant changedAt, String reason) implements DomainEvent {
        @Override
        public String key() {
            return "product:" + productId;
        }
    }
}
