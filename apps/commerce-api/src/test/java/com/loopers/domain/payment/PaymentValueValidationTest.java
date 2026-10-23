package com.loopers.domain.payment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentValueValidationTest {
    private static final UUID ATTEMPT_KEY = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @ParameterizedTest
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void acceptsNonNegativeStoredAmountRepresentation(long amount) {
        PaymentAttempt attempt = new PaymentAttempt(42L, ATTEMPT_KEY, "135135", "LP-ORD-0000000042", amount);
        PaymentTransaction transaction = new PaymentTransaction("135135", "LP-ORD-0000000042", "TEST-TX-0042",
            amount, PaymentTransaction.Status.SUCCESS);

        assertThat(attempt.startDispatch(ZonedDateTime.parse("2026-10-23T00:00:00Z"))).isTrue();
        assertThat(attempt.observe(transaction)).isEqualTo(PaymentObservationResult.APPLIED);
        assertThat(attempt.getAmount()).isEqualTo(amount);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    @ParameterizedTest
    @MethodSource("invalidAttempts")
    void rejectsInvalidLocalIdentityOrAmount(long orderId, UUID key, String account, String providerOrder,
                                             long amount) {
        assertThatThrownBy(() -> new PaymentAttempt(orderId, key, account, providerOrder, amount))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidTransactions")
    void rejectsIncompleteTransactionValues(String account, String providerOrder, String transactionKey,
                                            long amount, PaymentTransaction.Status status) {
        assertThatThrownBy(() -> new PaymentTransaction(account, providerOrder, transactionKey, amount, status))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidAttempts() {
        return Stream.of(
            Arguments.of(0L, ATTEMPT_KEY, "135135", "LP-ORD-0000000042", 7000L),
            Arguments.of(-1L, ATTEMPT_KEY, "135135", "LP-ORD-0000000042", 7000L),
            Arguments.of(42L, null, "135135", "LP-ORD-0000000042", 7000L),
            Arguments.of(42L, ATTEMPT_KEY, null, "LP-ORD-0000000042", 7000L),
            Arguments.of(42L, ATTEMPT_KEY, " ", "LP-ORD-0000000042", 7000L),
            Arguments.of(42L, ATTEMPT_KEY, "135135", null, 7000L),
            Arguments.of(42L, ATTEMPT_KEY, "135135", " ", 7000L),
            Arguments.of(42L, ATTEMPT_KEY, "135135", "LP-ORD-0000000042", -1L)
        );
    }

    private static Stream<Arguments> invalidTransactions() {
        return Stream.of(
            Arguments.of(null, "LP-ORD-0000000042", "TEST-TX-0042", 7000L, PaymentTransaction.Status.SUCCESS),
            Arguments.of(" ", "LP-ORD-0000000042", "TEST-TX-0042", 7000L, PaymentTransaction.Status.SUCCESS),
            Arguments.of("135135", null, "TEST-TX-0042", 7000L, PaymentTransaction.Status.SUCCESS),
            Arguments.of("135135", " ", "TEST-TX-0042", 7000L, PaymentTransaction.Status.SUCCESS),
            Arguments.of("135135", "LP-ORD-0000000042", null, 7000L, PaymentTransaction.Status.SUCCESS),
            Arguments.of("135135", "LP-ORD-0000000042", " ", 7000L, PaymentTransaction.Status.SUCCESS),
            Arguments.of("135135", "LP-ORD-0000000042", "TEST-TX-0042", -1L, PaymentTransaction.Status.SUCCESS),
            Arguments.of("135135", "LP-ORD-0000000042", "TEST-TX-0042", 7000L, null)
        );
    }
}
