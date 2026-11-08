package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.order.OrderConfirmedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonOutboxMessageSerializerTest {
    private final Instant recordedAt = Instant.parse("2026-11-04T01:02:03.123456Z");
    private final Instant confirmedAt = Instant.parse("2026-10-30T01:00:00.123456Z");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Clock clock = Clock.fixed(recordedAt, ZoneOffset.UTC);
    private final OrderConfirmedEvent event = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42", 42,
        confirmedAt, List.of(new OrderConfirmedEvent.Item(8, 1), new OrderConfirmedEvent.Item(7, 2)));

    @Test
    void storesTheOriginalSixFieldJsonAndDestinationSeparatelyFromRecordingTime() throws Exception {
        var saved = serializer("original-topic").serialize(event);
        assertThat(saved.getEventId()).isEqualTo(event.eventId());
        assertThat(saved.getAggregateType()).isEqualTo("Order");
        assertThat(saved.getAggregateId()).isEqualTo("42");
        assertThat(saved.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(saved.getSchemaVersion()).isEqualTo(1);
        assertThat(saved.getMessageKey()).isEqualTo("42");
        assertThat(saved.getTopic()).isEqualTo("original-topic");
        assertThat(saved.getCreatedAt()).isEqualTo(recordedAt);
        var json = mapper.readTree(saved.getPayload());
        assertThat(json.isObject()).isTrue();
        assertThat(json.size()).isEqualTo(6);
        assertThat(json.get("confirmedAt").asText()).isEqualTo(confirmedAt.toString());
        assertThat(json.get("orderId").isIntegralNumber()).isTrue();
        assertThat(json.get("items").get(0).get("productId").asLong()).isEqualTo(7);
        assertThat(json.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(mapper.treeToValue(json, OrderConfirmedEvent.class)).isEqualTo(event);
    }

    @Test
    void laterTopicChangesCannotChangeTheAlreadyCreatedMessage() {
        var saved = serializer("original-topic").serialize(event);
        var newer = serializer("new-topic").serialize(event);
        assertThat(saved.getTopic()).isEqualTo("original-topic");
        assertThat(newer.getTopic()).isEqualTo("new-topic");
        assertThat(saved.getPayload()).isEqualTo(newer.getPayload());
    }

    @Test
    void unknownTypesAndDuplicateMappingsFailRatherThanSilentlyLosingTheEvent() {
        assertThatThrownBy(() -> serializer("topic").serialize(new Probe("probe:1")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JsonOutboxMessageSerializer(mapper, clock,
            List.of(mapping("first"), mapping("second"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSecondEventTypeNeedsOnlyItsExplicitMapping() {
        var serializer = new JsonOutboxMessageSerializer(mapper, clock, List.of(mapping("orders"),
            new OutboxEventMapping<>(Probe.class, "Product", e -> 3, Probe::eventId, "products")));
        var saved = serializer.serialize(new Probe("probe:1"));
        assertThat(saved.getAggregateType()).isEqualTo("Product");
        assertThat(saved.getSchemaVersion()).isEqualTo(3);
        assertThat(saved.getTopic()).isEqualTo("products");
    }

    @Test
    void serializationErrorsEscapeWithoutLoggingThePayload() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.copy()).thenReturn(broken);
        when(broken.disable(any(com.fasterxml.jackson.databind.SerializationFeature.class))).thenReturn(broken);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("private-payload") { });
        var serializer = new JsonOutboxMessageSerializer(broken, clock, List.of(mapping("topic")));

        assertThatThrownBy(() -> serializer.serialize(event)).isInstanceOf(IllegalStateException.class)
            .hasMessage("Could not serialize external event").hasNoCause();
    }

    private JsonOutboxMessageSerializer serializer(String topic) {
        return new JsonOutboxMessageSerializer(mapper, clock, List.of(mapping(topic)));
    }

    private OutboxEventMapping<OrderConfirmedEvent> mapping(String topic) {
        return new OutboxEventMapping<>(OrderConfirmedEvent.class, "Order", OrderConfirmedEvent::schemaVersion,
            e -> Long.toString(e.orderId()), topic);
    }

    record Probe(String eventId) implements DomainEvent {
        @Override public String eventType() { return "ProductChanged"; }
        @Override public String key() { return eventId; }
    }
}
