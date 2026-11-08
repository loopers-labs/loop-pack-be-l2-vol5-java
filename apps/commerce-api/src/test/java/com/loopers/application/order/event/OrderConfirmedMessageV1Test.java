package com.loopers.application.order.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @DisplayName("주문 확정 메시지는 계약 필드와 orderId 문자열 key를 유지한다.")
    @Test
    void writesContractFieldsAndOrderIdKey() throws Exception {
        OrderConfirmedMessageV1 message = OrderConfirmedMessageV1.from(
            event(ZonedDateTime.parse("2026-09-07T10:00:00+09:00")));

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
        assertThat(message.key()).isEqualTo("42");
    }

    @DisplayName("확정 시각은 UTC로 바꾸고 마이크로초 정밀도를 보존한다.")
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
