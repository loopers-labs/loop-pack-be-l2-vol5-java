package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonOrderEventSerializerTest {

    // W6 계약 예시(docs/week6/design.md 2절, 주문 42)
    private static final OrderConfirmedEvent CONTRACT_EXAMPLE = new OrderConfirmedEvent(
        "OrderConfirmed",
        1,
        "order-confirmed:v1:42",
        42L,
        "2026-09-07T01:00:00Z",
        List.of(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1))
    );

    private final JacksonOrderEventSerializer serializer = new JacksonOrderEventSerializer();

    @DisplayName("계약의 여섯 필드만, 계약 예시와 같은 순서·값의 JSON으로 만든다.")
    @Test
    void writesOnlyContractFields() {
        // act
        String payload = serializer.serialize(CONTRACT_EXAMPLE);

        // assert
        assertThat(payload).isEqualTo(
            "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:42\",\"orderId\":42,"
                + "\"confirmedAt\":\"2026-09-07T01:00:00Z\",\"items\":[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]}"
        );
    }

    @DisplayName("W6 producer의 JsonSerializer가 같은 이벤트로 만들던 record 값과 바이트가 같다.")
    @Test
    void matchesW6ProducerBytes() {
        // arrange
        byte[] w6RecordValue;
        try (JsonSerializer<OrderConfirmedEvent> w6Serializer = new JsonSerializer<>()) {
            w6RecordValue = w6Serializer.serialize("order-events", CONTRACT_EXAMPLE);
        }

        // act
        String payload = serializer.serialize(CONTRACT_EXAMPLE);

        // assert
        assertThat(payload.getBytes(StandardCharsets.UTF_8)).isEqualTo(w6RecordValue);
    }
}
