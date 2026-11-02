package com.loopers.infrastructure.order.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.event.OrderConfirmedEvent;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderConfirmedMessageV1Test {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DisplayName("주문 42의 확정 이벤트를 계약 필드만 담은 JSON과 orderId 문자열 key로 만든다.")
    @Test
    void writesContractFieldsAndOrderIdKey() throws Exception {
        OrderConfirmedMessageV1 message = OrderConfirmedMessageV1.from(event(ZonedDateTime.parse("2026-09-07T10:00:00+09:00")));

        JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(actual).isEqualTo(objectMapper.readTree("""
            {
              "eventType": "OrderConfirmed",
              "schemaVersion": 1,
              "eventId": "order-confirmed:v1:42",
              "orderId": 42,
              "confirmedAt": "2026-09-07T01:00:00Z",
              "items": [
                {"productId": 7, "quantity": 2},
                {"productId": 8, "quantity": 1}
              ]
            }
            """));
        assertThat(message.key()).isEqualTo("42").isEqualTo(actual.get("orderId").asText());
    }

    @DisplayName("다른 시간대의 확정 시각을 UTC로 바꾸고 마이크로초가 있으면 그대로 남긴다.")
    @Test
    void writesConfirmedAtInUtcKeepingMicros() {
        OrderConfirmedMessageV1 message = OrderConfirmedMessageV1.from(
            event(ZonedDateTime.parse("2026-09-07T10:00:00.123456+09:00")));

        assertThat(message.confirmedAt()).isEqualTo("2026-09-07T01:00:00.123456Z");
    }

    private OrderConfirmedEvent event(ZonedDateTime confirmedAt) {
        return OrderConfirmedEvent.from(Order.reconstitute(42L, 1L, OrderStatus.CONFIRMED, 400L, 400L,
            PaymentResult.SUCCESS, PaymentMethod.PG, confirmedAt, List.of(
                OrderItem.create(8L, "Pegasus", 200L, 1),
                OrderItem.create(7L, "Air Max", 100L, 2)
            )));
    }
}
