package com.loopers.infrastructure.order.messaging;

import com.loopers.domain.order.vo.OrderConfirmed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonOrderConfirmedSerializerTest {

    private static final OrderConfirmed EVENT = new OrderConfirmed("order-confirmed:v1:42", 42L,
        ZonedDateTime.parse("2026-09-07T10:00:00.123456+09:00[Asia/Seoul]"),
        List.of(new OrderConfirmed.Item(7L, 2), new OrderConfirmed.Item(8L, 1)));

    private final JsonOrderConfirmedSerializer serializer = new JsonOrderConfirmedSerializer();

    @DisplayName("저장 payload 는 6주차 v1 계약 JSON 이다. (6주차 설계 2)")
    @Test
    void writesV1Contract() {
        String payload = serializer.serialize(EVENT);

        assertThat(payload).isEqualTo("{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:42\","
            + "\"orderId\":42,\"confirmedAt\":\"2026-09-07T01:00:00.123456Z\","
            + "\"items\":[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]}");
    }

    @DisplayName("6주차 직접 발행의 JsonSerializer 가 보내던 바이트와 같아, relay 로 바꿔도 consumer 가 받는 값이 같다.")
    @Test
    void matchesBytesOfWeek6Publisher() {
        try (JsonSerializer<Object> week6 = new JsonSerializer<>()) {
            week6.setAddTypeInfo(false);

            byte[] published = week6.serialize("order-events", OrderConfirmedMessage.from(EVENT));

            assertThat(serializer.serialize(EVENT)).isEqualTo(new String(published, StandardCharsets.UTF_8));
        }
    }
}
