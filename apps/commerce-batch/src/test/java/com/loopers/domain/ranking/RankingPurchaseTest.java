package com.loopers.domain.ranking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankingPurchaseTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-17T01:00:00.123456789Z");

    @Test
    void preservesSourceFactsIncludingNanosecondsAndMaximumValues() {
        var purchase = new RankingPurchase(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, CONFIRMED_AT);

        assertThat(purchase.orderId()).isEqualTo(Long.MAX_VALUE);
        assertThat(purchase.productId()).isEqualTo(Long.MAX_VALUE);
        assertThat(purchase.quantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(purchase.confirmedAt()).isEqualTo(CONFIRMED_AT);
    }

    @ParameterizedTest
    @MethodSource("invalidPurchases")
    void rejectsMissingTimeAndNonPositiveSourceValues(long orderId, long productId, int quantity,
                                                      Instant confirmedAt) {
        assertThatThrownBy(() -> new RankingPurchase(orderId, productId, quantity, confirmedAt))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidPurchases() {
        return Stream.of(
            Arguments.of(0L, 7L, 2, CONFIRMED_AT),
            Arguments.of(-1L, 7L, 2, CONFIRMED_AT),
            Arguments.of(42L, 0L, 2, CONFIRMED_AT),
            Arguments.of(42L, -1L, 2, CONFIRMED_AT),
            Arguments.of(42L, 7L, 0, CONFIRMED_AT),
            Arguments.of(42L, 7L, -1, CONFIRMED_AT),
            Arguments.of(42L, 7L, 2, null));
    }
}
