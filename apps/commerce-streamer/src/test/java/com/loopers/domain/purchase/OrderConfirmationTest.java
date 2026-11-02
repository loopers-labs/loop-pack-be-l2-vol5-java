package com.loopers.domain.purchase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");

    @DisplayName("같은 상품의 양수 수량을 합산하고 상품 ID 순서로 정규화한다.")
    @Test
    void normalizesProductsAndPreservesConfirmationTime() {
        OrderConfirmation event = new OrderConfirmation("event-42", 42L, CONFIRMED_AT, List.of(
            new OrderConfirmation.Item(8L, 1),
            new OrderConfirmation.Item(7L, 1),
            new OrderConfirmation.Item(7L, 1)));

        assertThat(event.items()).containsExactly(
            new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1));
        assertThat(event.activities()).containsExactly(
            ProductPurchaseActivity.create(42L, 7L, 2, CONFIRMED_AT),
            ProductPurchaseActivity.create(42L, 8L, 1, CONFIRMED_AT));
        assertThatThrownBy(() -> event.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @DisplayName("이벤트 식별자는 해석하지 않고 그대로 보존한다.")
    @Test
    void keepsOpaqueEventId() {
        OrderConfirmation event = new OrderConfirmation("opaque-event", 42L, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7L, 2)));

        assertThat(event.eventId()).isEqualTo("opaque-event");
    }

    @DisplayName("주문 식별자는 양수여야 한다.")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsInvalidOrderId(long orderId) {
        assertThatThrownBy(() -> new OrderConfirmation("event", orderId, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7L, 1))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("상품 식별자는 양수여야 한다.")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsInvalidProductId(long productId) {
        assertThatThrownBy(() -> new OrderConfirmation.Item(productId, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("각 품목의 수량은 합산 전에 양수여야 한다.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidQuantity(int quantity) {
        assertThatThrownBy(() -> new OrderConfirmation.Item(7L, quantity))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("상품별 합산 수량이 int 범위를 넘으면 거절한다.")
    @Test
    void rejectsQuantityOverflow() {
        assertThatThrownBy(() -> new OrderConfirmation("event", 42L, CONFIRMED_AT, List.of(
            new OrderConfirmation.Item(7L, Integer.MAX_VALUE), new OrderConfirmation.Item(7L, 1))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("빈 식별자·확정 시각 누락·빈 품목은 거절한다.")
    @Test
    void rejectsMissingFacts() {
        List<OrderConfirmation.Item> items = List.of(new OrderConfirmation.Item(7L, 1));

        assertThatThrownBy(() -> new OrderConfirmation(" ", 42L, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmation(null, 42L, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmation("event", 42L, null, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmation("event", 42L, CONFIRMED_AT, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
