package com.loopers.domain.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발행 payload 의 값 규칙 (6주차 설계 1절·D1). JSON 모양은 3-2b 의 발행기 계약 테스트가 본다.
 */
class OrderConfirmedEventTest {

    /** Asia/Seoul 시각으로 넣어도 payload 는 UTC Instant 다. */
    private static final ZonedDateTime CONFIRMED_AT_SEOUL = ZonedDateTime.parse("2026-09-07T10:00:00.123+09:00[Asia/Seoul]");

    @DisplayName("주문 42(상품 8×1, 7×2)의 payload 는 eventType·schemaVersion·eventId·UTC confirmedAt·productId 오름차순 items 를 갖는다.")
    @Test
    void buildsPayloadForOrder42() {
        OrderConfirmedEvent event = OrderConfirmedEvent.of(42L, CONFIRMED_AT_SEOUL,
            List.of(new OrderItem(8L, 1, 1000L), new OrderItem(7L, 2, 1000L)));

        assertThat(event.eventType()).isEqualTo("OrderConfirmed");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(event.orderId()).isEqualTo(42L);
        assertThat(event.key()).isEqualTo("42");
        assertThat(event.confirmedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:00.123Z"));
        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1));
    }

    @DisplayName("같은 상품 품목이 여러 줄이면 수량을 합산해 한 줄로 만든다.")
    @Test
    void mergesSameProduct() {
        OrderConfirmedEvent event = OrderConfirmedEvent.of(42L, CONFIRMED_AT_SEOUL,
            List.of(new OrderItem(7L, 1, 1000L), new OrderItem(7L, 1, 1000L)));

        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(7L, 2));
    }

    @DisplayName("eventId 는 orderId 에서만 계산되므로 몇 번 만들어도 같다.")
    @Test
    void eventIdIsStable() {
        assertThat(OrderConfirmedEvent.eventIdOf(42L)).isEqualTo("order-confirmed:v1:42");
        assertThat(OrderConfirmedEvent.of(42L, CONFIRMED_AT_SEOUL, List.of(new OrderItem(7L, 2, 1000L))).eventId())
            .isEqualTo(OrderConfirmedEvent.of(42L, CONFIRMED_AT_SEOUL.plusHours(1), List.of(new OrderItem(7L, 2, 1000L))).eventId());
    }
}
