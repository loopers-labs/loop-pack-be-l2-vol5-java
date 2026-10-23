package com.loopers.domain.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAttemptFingerprintTest {
    private static final ZonedDateTime START = ZonedDateTime.parse("2026-10-23T00:00:00Z");

    @Test
    void fingerprintConstructorRequiresExplicitComparisonMaterial() {
        assertThatThrownBy(() -> new PaymentAttempt(42L, UUID.randomUUID(), "135135", "LP-ORD-42", 7000L, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Payment request fingerprint is required");
    }

    @Test
    void storesTheOriginalFingerprintWithoutExposingMutableArrays() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 0x42);
        byte[] expected = bytes.clone();
        var attempt = attempt(new PaymentRequestFingerprint(1, 7, bytes));
        bytes[0] = 0;
        attempt.requireRequestFingerprint().digest()[1] = 0;

        assertThat(Arrays.equals(attempt.requireRequestFingerprint().digest(), expected)).isTrue();
        assertThat(attempt.requireRequestFingerprint().fingerprintVersion()).isEqualTo(1);
        assertThat(attempt.requireRequestFingerprint().keyVersion()).isEqualTo(7);
        assertThat(attempt.getRequestFingerprint()).isPresent();
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void allPaymentStatesPreserveTheOriginalFingerprint(PaymentAttemptStatus status) {
        var original = new PaymentRequestFingerprint(1, 7, new byte[32]);
        var attempt = attempt(original);
        moveTo(attempt, status);

        assertThat(Arrays.equals(attempt.requireRequestFingerprint().digest(), original.digest())).isTrue();
        assertThat(attempt.requireRequestFingerprint().fingerprintVersion()).isEqualTo(1);
        assertThat(attempt.requireRequestFingerprint().keyVersion()).isEqualTo(7);
        assertThat(attempt.getStatus()).isEqualTo(status);
        assertThat(attempt.startDispatch(START.plusDays(1))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void legacyAbsenceDoesNotInventAFingerprintOrPreventResultRecovery(PaymentAttemptStatus status) {
        var attempt = new PaymentAttempt(42L, UUID.randomUUID(), "135135", "LP-ORD-42", 7000L);
        moveTo(attempt, status);

        assertThat(attempt.getRequestFingerprint()).isEmpty();
        assertThatThrownBy(attempt::requireRequestFingerprint)
            .isInstanceOf(PaymentFingerprintUnavailableException.class)
            .hasMessage("Payment request fingerprint is unavailable");
        assertThat(attempt.getStatus()).isEqualTo(status);
        assertThat(attempt.getSendStartedAt()).isEqualTo(START);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "32,NULL,NULL", "NULL,1,NULL", "NULL,NULL,7",
        "32,1,NULL", "32,NULL,7", "NULL,1,7",
        "0,1,7", "31,1,7", "33,1,7", "32,0,7", "32,-1,7", "32,1,0", "32,1,-1"
    }, nullValues = "NULL")
    void corruptLoadedRepresentationIsUnavailableAndDoesNotChangePaymentState(Integer length, Integer format, Integer key) {
        var attempt = new PaymentAttempt(42L, UUID.randomUUID(), "135135", "LP-ORD-42", 7000L);
        attempt.startDispatch(START);
        // 손상된 저장 값을 읽는 방어 분기만 주입한다. 업무용 수정 메서드를 추가하지 않는다.
        ReflectionTestUtils.setField(attempt, "requestFingerprint", length == null ? null : new byte[length]);
        ReflectionTestUtils.setField(attempt, "fingerprintVersion", format);
        ReflectionTestUtils.setField(attempt, "keyVersion", key);

        assertThatThrownBy(attempt::getRequestFingerprint)
            .isInstanceOf(PaymentFingerprintUnavailableException.class)
            .hasMessage("Payment request fingerprint is unavailable");
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.NOT_OBSERVED);
        assertThat(attempt.getSendStartedAt()).isEqualTo(START);
    }

    private static PaymentAttempt attempt(PaymentRequestFingerprint fingerprint) {
        return new PaymentAttempt(42L, UUID.randomUUID(), "135135", "LP-ORD-42", 7000L, fingerprint);
    }

    private static void moveTo(PaymentAttempt attempt, PaymentAttemptStatus status) {
        attempt.startDispatch(START);
        if (status == PaymentAttemptStatus.UNKNOWN) {
            attempt.markUnknown();
        } else if (status != PaymentAttemptStatus.NOT_OBSERVED) {
            attempt.observe(new PaymentTransaction("135135", "LP-ORD-42", "TX-42", 7000L,
                PaymentTransaction.Status.valueOf(status.name())));
        }
    }
}
