package com.loopers.application.order;

import com.loopers.domain.payment.PaymentAttempt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderOrderIdTest {
    @ParameterizedTest
    @CsvSource({
        "1,LP-ORD-0000000001",
        "42,LP-ORD-0000000042",
        "9999999999,LP-ORD-9999999999",
        "10000000000,LP-ORD-10000000000",
        "9223372036854775807,LP-ORD-9223372036854775807"
    })
    void newOrderUsesStableDecimalIdentityWithoutTruncatingLong(long orderId, String expected) {
        assertThat(ProviderOrderId.resolve(orderId, null)).isEqualTo(expected);
        assertThat(ProviderOrderId.resolve(orderId, null)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy-order-42", "LP-ORD-42", "OTHER-PROVIDER-ORDER-0042"})
    void existingAttemptKeepsItsStoredIdentity(String storedId) {
        var attempt = new PaymentAttempt(42L, UUID.randomUUID(), "135135", storedId, 7000L);

        assertThat(ProviderOrderId.resolve(42L, attempt)).isEqualTo(storedId);
        assertThat(attempt.getProviderOrderId()).isEqualTo(storedId);
        assertThat(attempt.getSendStartedAt()).isNull();
        assertThat(attempt.getRequestFingerprint()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void invalidInternalOrderIdentityIsRejected(long orderId) {
        assertThatThrownBy(() -> ProviderOrderId.resolve(orderId, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptForAnotherOrderIsNotUsedAsAnIdentityMapping() {
        var attempt = new PaymentAttempt(43L, UUID.randomUUID(), "135135", "LEGACY-43", 7000L);

        assertThatThrownBy(() -> ProviderOrderId.resolve(42L, attempt))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Stored payment attempt belongs to another order");
        assertThat(attempt.getProviderOrderId()).isEqualTo("LEGACY-43");
    }
}
