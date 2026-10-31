package com.loopers.domain.order;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-25T01:00:00Z");

    @Test
    void derivesStableEventIdentityFromOrderId() {
        OrderConfirmed first = new OrderConfirmed(42L, CONFIRMED_AT, List.of(new OrderConfirmed.Item(7L, 2)));
        OrderConfirmed repeated = new OrderConfirmed(42L, CONFIRMED_AT, List.of(new OrderConfirmed.Item(7L, 2)));
        OrderConfirmed other = new OrderConfirmed(43L, CONFIRMED_AT, List.of(new OrderConfirmed.Item(7L, 2)));

        assertThat(first.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(repeated.eventId()).isEqualTo(first.eventId());
        assertThat(other.eventId()).isEqualTo("order-confirmed:v1:43");
    }

    @Test
    void combinesDuplicateProductsAndCopiesItems() {
        List<OrderConfirmed.Item> source = new ArrayList<>(List.of(
            new OrderConfirmed.Item(8L, 1),
            new OrderConfirmed.Item(7L, 2),
            new OrderConfirmed.Item(7L, 3)
        ));

        OrderConfirmed event = new OrderConfirmed(42L, CONFIRMED_AT, source);
        source.clear();

        assertThat(event.items()).containsExactly(
            new OrderConfirmed.Item(7L, 5), new OrderConfirmed.Item(8L, 1)
        );
        assertThatThrownBy(() -> event.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingOrderFactsAndInvalidItems() {
        List<OrderConfirmed.Item> items = List.of(new OrderConfirmed.Item(7L, 2));

        assertThatThrownBy(() -> new OrderConfirmed(null, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmed(0L, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmed(42L, null, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmed(42L, CONFIRMED_AT, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmed.Item(7L, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmed.Item(-1L, 2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsQuantityOverflowWhenCombiningProducts() {
        assertThatThrownBy(() -> new OrderConfirmed(42L, CONFIRMED_AT, List.of(
            new OrderConfirmed.Item(7L, Integer.MAX_VALUE), new OrderConfirmed.Item(7L, 1)
        ))).isInstanceOf(ArithmeticException.class);
    }
}
