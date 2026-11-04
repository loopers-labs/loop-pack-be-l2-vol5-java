package com.loopers.domain.purchase;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPurchaseTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T01:00:00.123456Z");
    private static final String EVENT_ID = "order-confirmed:v1:42";

    @Test
    void normalizesQuantitiesAndCopiesItems() {
        List<OrderPurchase.Item> source = new ArrayList<>(List.of(
            new OrderPurchase.Item(8L, 3), new OrderPurchase.Item(7L, 2), new OrderPurchase.Item(7L, 3)));

        OrderPurchase purchase = new OrderPurchase(EVENT_ID, 42L, CONFIRMED_AT, source);
        source.clear();

        assertThat(purchase.items()).containsExactly(new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3));
        assertThatThrownBy(() -> purchase.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingFactsAndInvalidItems() {
        List<OrderPurchase.Item> items = List.of(new OrderPurchase.Item(7L, 5));

        assertThatThrownBy(() -> new OrderPurchase(EVENT_ID, null, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase(EVENT_ID, 0L, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase(EVENT_ID, 42L, null, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase(EVENT_ID, 42L, CONFIRMED_AT, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase(EVENT_ID, 42L, CONFIRMED_AT, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase(EVENT_ID, 42L, CONFIRMED_AT, Arrays.asList((OrderPurchase.Item) null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase(null, 42L, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase(" ", 42L, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase("x".repeat(256), 42L, CONFIRMED_AT, items))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase.Item(null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase.Item(-1L, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPurchase.Item(7L, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsQuantityOverflow() {
        assertThatThrownBy(() -> new OrderPurchase(EVENT_ID, 42L, CONFIRMED_AT, List.of(
            new OrderPurchase.Item(7L, Integer.MAX_VALUE), new OrderPurchase.Item(7L, 1))))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsInvalidStoredActivities() {
        assertThatThrownBy(() -> new ProductPurchaseActivity(0L, 7L, 5, CONFIRMED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductPurchaseActivity(42L, 0L, 5, CONFIRMED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductPurchaseActivity(42L, 7L, 0, CONFIRMED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductPurchaseActivity(42L, 7L, 5, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
