package com.loopers.purchase.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.purchase.application.event.OrderConfirmedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedMessageParserTest {

    private final OrderConfirmedMessageParser parser = new OrderConfirmedMessageParser(new ObjectMapper());

    @Test
    void mapsPayloadCompatibleWithPurchaseActivityDtoWithoutSchemaVersion() {
        String payload = """
            {
              "eventId":"event-42",
              "orderId":42,
              "confirmedAt":"2026-09-07T01:00:00Z",
              "items":[{"productId":7,"quantity":2}],
              "additionalField":"accepted"
            }
            """;

        OrderConfirmedEvent event = parser.parse(payload.getBytes());

        assertThat(event.eventId()).isEqualTo("event-42");
        assertThat(event.orderId()).isEqualTo(42L);
        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(7L, 2));
    }

    @Test
    void rejectsPayloadMissingFieldRequiredByDto() {
        String payload = """
            {
              "eventType":"OrderConfirmed",
              "eventId":"event-42",
              "orderId":42,
              "confirmedAt":"2026-09-07T01:00:00Z",
              "items":[{"productId":7}]
            }
            """;

        assertThatThrownBy(() -> parser.parse(payload.getBytes()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OrderConfirmed item is incompatible with purchase activity DTO");
    }

}
