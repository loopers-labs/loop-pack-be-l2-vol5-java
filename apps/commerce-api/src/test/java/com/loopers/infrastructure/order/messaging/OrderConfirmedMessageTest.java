package com.loopers.infrastructure.order.messaging;

import com.loopers.domain.order.vo.OrderConfirmed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class OrderConfirmedMessageTest {

    @DisplayName("확정 사실을 v1 계약으로 옮기고, 확정 시각은 UTC 마이크로초 6자리 문자열로 쓴다. (6주차 설계 2, D-83)")
    @Test
    void mapsToContract() {
        // arrange
        OrderConfirmed event = new OrderConfirmed("order-confirmed:v1:42", 42L,
            ZonedDateTime.parse("2026-09-07T10:00:00.123456+09:00[Asia/Seoul]"),
            List.of(new OrderConfirmed.Item(7L, 2), new OrderConfirmed.Item(8L, 1)));

        // act
        OrderConfirmedMessage message = OrderConfirmedMessage.from(event);

        // assert
        assertAll(
            () -> assertThat(message.eventType()).isEqualTo("OrderConfirmed"),
            () -> assertThat(message.schemaVersion()).isEqualTo(1),
            () -> assertThat(message.eventId()).isEqualTo("order-confirmed:v1:42"),
            () -> assertThat(message.orderId()).isEqualTo(42L),
            () -> assertThat(message.confirmedAt()).isEqualTo("2026-09-07T01:00:00.123456Z"),
            () -> assertThat(message.items()).containsExactly(new OrderConfirmedMessage.Item(7L, 2), new OrderConfirmedMessage.Item(8L, 1))
        );
    }

    @DisplayName("마이크로초가 0 이어도 6자리를 채워, 같은 사실이 늘 같은 문자열이 된다.")
    @Test
    void keepsSixFractionDigits() {
        OrderConfirmed event = new OrderConfirmed("order-confirmed:v1:1", 1L,
            ZonedDateTime.parse("2026-09-07T01:00:00Z"), List.of(new OrderConfirmed.Item(7L, 1)));

        assertThat(OrderConfirmedMessage.from(event).confirmedAt()).isEqualTo("2026-09-07T01:00:00.000000Z");
    }
}
