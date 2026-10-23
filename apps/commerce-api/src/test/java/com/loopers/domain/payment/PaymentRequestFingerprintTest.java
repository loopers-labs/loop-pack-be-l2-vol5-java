package com.loopers.domain.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRequestFingerprintTest {
    @Test
    void storedDigestIsNotChangedThroughConstructorOrAccessorArrays() {
        byte[] original = new byte[32];
        Arrays.fill(original, (byte) 0x42);
        var fingerprint = new PaymentRequestFingerprint(1, 7, original);
        byte[] expected = original.clone();

        original[0] = 0;
        byte[] exposed = fingerprint.digest();
        exposed[1] = 0;

        assertThat(Arrays.equals(fingerprint.digest(), expected)).isTrue();
        assertThat(fingerprint.toString()).isEqualTo("PaymentRequestFingerprint[REDACTED]");
    }

    @ParameterizedTest(name = "invalid fingerprint representation {index}")
    @MethodSource("invalidFingerprints")
    void requiresPositiveVersionsAndFullDigest(int formatVersion, int keyVersion, byte[] digest) {
        assertThatThrownBy(() -> new PaymentRequestFingerprint(formatVersion, keyVersion, digest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid payment fingerprint representation");
    }

    @ParameterizedTest(name = "invalid input boundary {index}")
    @MethodSource("invalidInputs")
    void invalidInputsAreRejectedWithoutIncludingTheirValues(long userId, long orderId, long amount,
                                                             String type, String card) {
        assertThatThrownBy(() -> new PaymentFingerprintInput(userId, orderId, amount, type, card))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid payment fingerprint input");
    }

    private static Stream<Arguments> invalidFingerprints() {
        return Stream.of(Arguments.of(0, 7, new byte[32]), Arguments.of(-1, 7, new byte[32]),
            Arguments.of(1, 0, new byte[32]), Arguments.of(1, -1, new byte[32]), Arguments.of(1, 7, null),
            Arguments.of(1, 7, new byte[0]), Arguments.of(1, 7, new byte[31]), Arguments.of(1, 7, new byte[33]));
    }

    private static Stream<Arguments> invalidInputs() {
        return Stream.of(Arguments.of(0L, 42L, 7000L, "TEST", "0000-0000-0000-0000"),
            Arguments.of(-1L, 42L, 7000L, "TEST", "0000-0000-0000-0000"),
            Arguments.of(1L, 0L, 7000L, "TEST", "0000-0000-0000-0000"),
            Arguments.of(1L, -1L, 7000L, "TEST", "0000-0000-0000-0000"),
            Arguments.of(1L, 42L, -1L, "TEST", "0000-0000-0000-0000"),
            Arguments.of(1L, 42L, 7000L, null, "0000-0000-0000-0000"),
            Arguments.of(1L, 42L, 7000L, " ", "0000-0000-0000-0000"),
            Arguments.of(1L, 42L, 7000L, "TEST", null), Arguments.of(1L, 42L, 7000L, "TEST", " "));
    }
}
