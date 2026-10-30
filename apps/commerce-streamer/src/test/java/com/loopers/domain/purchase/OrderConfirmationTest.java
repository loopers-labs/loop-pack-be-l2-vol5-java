package com.loopers.domain.purchase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T00:00:00.123456789Z");

    @Test
    void normalizesSplitItemsAndTheirOrderAsTheSameFact() {
        var split = event("event-42", 42, CONFIRMED_AT, List.of(new OrderConfirmation.Item(8, 1),
            new OrderConfirmation.Item(7, 1), new OrderConfirmation.Item(7, 1)));
        var combined = event("event-42", 42, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7, 2), new OrderConfirmation.Item(8, 1)));

        assertThat(split).isEqualTo(combined);
        assertThat(split.items()).containsExactly(new OrderConfirmation.Item(7, 2), new OrderConfirmation.Item(8, 1));
    }

    @Test
    void preservesArbitraryEventIdentityAndExactTimeForLaterConflictDetection() {
        var original = event("external-event", 42, CONFIRMED_AT, List.of(new OrderConfirmation.Item(7, 2)));

        assertThat(original.eventId()).isEqualTo("external-event");
        assertThat(original.confirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(original).isNotEqualTo(event("other-event", 42, CONFIRMED_AT, original.items()));
        assertThat(original).isNotEqualTo(event("external-event", 43, CONFIRMED_AT, original.items()));
        assertThat(original).isNotEqualTo(event("external-event", 42, CONFIRMED_AT.plusNanos(1), original.items()));
        assertThat(original).isNotEqualTo(event("external-event", 42, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7, 3))));
        assertThat(event("external-event ", 42, CONFIRMED_AT, original.items())).isNotEqualTo(original);
    }

    @Test
    void keepsAnImmutableCopyAndDoesNotRetainSourceList() {
        var source = new ArrayList<>(List.of(new OrderConfirmation.Item(7, 2)));
        var event = event("event-42", 42, CONFIRMED_AT, source);

        source.clear();

        assertThat(event.items()).containsExactly(new OrderConfirmation.Item(7, 2));
        assertThatThrownBy(() -> event.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesMaximumIdsAndExactMaximumCombinedQuantity() {
        var event = event("event-max", Long.MAX_VALUE, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(Long.MAX_VALUE, Integer.MAX_VALUE - 1),
                new OrderConfirmation.Item(Long.MAX_VALUE, 1)));

        assertThat(event.orderId()).isEqualTo(Long.MAX_VALUE);
        assertThat(event.items()).containsExactly(new OrderConfirmation.Item(Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void rejectsOverflowInsteadOfWrappingTheCombinedQuantity() {
        assertThatThrownBy(() -> event("event-42", 42, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7, Integer.MAX_VALUE), new OrderConfirmation.Item(7, 1))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidFacts")
    void rejectsMissingOrUnsupportedFacts(String type, int version, String eventId, long orderId,
                                         Instant confirmedAt, List<OrderConfirmation.Item> items) {
        assertThatThrownBy(() -> new OrderConfirmation(type, version, eventId, orderId, confirmedAt, items))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidItems")
    void rejectsNonPositiveProductIdentityAndQuantity(long productId, int quantity) {
        assertThatThrownBy(() -> new OrderConfirmation.Item(productId, quantity))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private OrderConfirmation event(String eventId, long orderId, Instant confirmedAt, List<OrderConfirmation.Item> items) {
        return new OrderConfirmation("OrderConfirmed", 1, eventId, orderId, confirmedAt, items);
    }

    private static Stream<Arguments> invalidFacts() {
        var items = List.of(new OrderConfirmation.Item(7, 2));
        return Stream.of(
            Arguments.of(null, 1, "event-42", 42L, CONFIRMED_AT, items),
            Arguments.of("OrderCancelled", 1, "event-42", 42L, CONFIRMED_AT, items),
            Arguments.of("OrderConfirmed", 2, "event-42", 42L, CONFIRMED_AT, items),
            Arguments.of("OrderConfirmed", 1, null, 42L, CONFIRMED_AT, items),
            Arguments.of("OrderConfirmed", 1, " \t", 42L, CONFIRMED_AT, items),
            Arguments.of("OrderConfirmed", 1, "event-42", 0L, CONFIRMED_AT, items),
            Arguments.of("OrderConfirmed", 1, "event-42", -1L, CONFIRMED_AT, items),
            Arguments.of("OrderConfirmed", 1, "event-42", 42L, null, items),
            Arguments.of("OrderConfirmed", 1, "event-42", 42L, CONFIRMED_AT, null),
            Arguments.of("OrderConfirmed", 1, "event-42", 42L, CONFIRMED_AT, List.of()),
            Arguments.of("OrderConfirmed", 1, "event-42", 42L, CONFIRMED_AT,
                Arrays.asList(new OrderConfirmation.Item(7, 2), null)));
    }

    private static Stream<Arguments> invalidItems() {
        return Stream.of(Arguments.of(0L, 1), Arguments.of(-1L, 1), Arguments.of(7L, 0), Arguments.of(7L, -1));
    }
}
