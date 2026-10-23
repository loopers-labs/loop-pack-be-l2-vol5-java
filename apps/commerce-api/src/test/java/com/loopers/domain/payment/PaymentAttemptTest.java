package com.loopers.domain.payment;

import com.loopers.domain.payment.PaymentAttempt.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAttemptTest {

    private static PaymentAttempt requested() {
        return PaymentAttempt.start(42L, "attempt-abc", 3_000L, "TEST", "0000-0000-0000-0000");
    }

    @Test
    void startsRequestedWithProviderOrderIdButWithoutTransactionKey() {
        PaymentAttempt attempt = requested();

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(attempt.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(attempt.getTransactionKey()).isNull();
    }

    @Test
    void matchesTheOriginalAmountAndCardInput() {
        assertThat(requested().matchesRequest(3_000L, "TEST", "0000-0000-0000-0000")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "3001, TEST, 0000-0000-0000-0000",
        "3000, OTHER, 0000-0000-0000-0000",
        "3000, TEST, 1111-1111-1111-1111"
    })
    void doesNotMatchChangedAmountOrCardInput(long amount, String cardType, String cardNo) {
        assertThat(requested().matchesRequest(amount, cardType, cardNo)).isFalse();
    }

    @Test
    void distinguishesCardInputsWhosePlainConcatenationWouldBeTheSame() {
        PaymentAttempt attempt = PaymentAttempt.start(42L, "attempt-abc", 3_000L, "TE", "ST0000");

        assertThat(attempt.matchesRequest(3_000L, "TEST", "0000")).isFalse();
    }

    @Test
    void acceptedCreateMovesRequestedToPendingAndKeepsTransactionKey() {
        PaymentAttempt attempt = requested();

        assertThat(attempt.markPending("TEST-TX-1")).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getTransactionKey()).isEqualTo("TEST-TX-1");
    }

    @Test
    void lostCreateResponseMovesRequestedToUnknownWithoutTransactionKey() {
        PaymentAttempt attempt = requested();

        assertThat(attempt.markUnknown()).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.getTransactionKey()).isNull();
    }

    @Test
    void lookupFindingProcessingTransactionMovesUnknownToPending() {
        PaymentAttempt attempt = requested();
        attempt.markUnknown();

        assertThat(attempt.markPending("TEST-TX-1")).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getTransactionKey()).isEqualTo("TEST-TX-1");
    }

    @Test
    void lookupSuccessCompletesPendingAttempt() {
        PaymentAttempt attempt = requested();
        attempt.markPending("TEST-TX-1");

        assertThat(attempt.succeed("TEST-TX-1")).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    @Test
    void lookupFailureCompletesUnknownAttemptAndKeepsTransactionKey() {
        PaymentAttempt attempt = requested();
        attempt.markUnknown();

        assertThat(attempt.fail("TEST-TX-1")).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getTransactionKey()).isEqualTo("TEST-TX-1");
    }

    @Test
    void callbackCanCompleteAttemptBeforeCreateResponseIsRecorded() {
        PaymentAttempt attempt = requested();

        assertThat(attempt.succeed("TEST-TX-1")).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    @Test
    void lateCreateResponseDoesNotMoveCompletedAttemptBackward() {
        PaymentAttempt attempt = requested();
        attempt.succeed("TEST-TX-1");

        assertThat(attempt.markPending("TEST-TX-1")).isEqualTo(Result.IGNORED);
        assertThat(attempt.markUnknown()).isEqualTo(Result.IGNORED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    @Test
    void lateTimeoutDoesNotMovePendingBackToUnknown() {
        PaymentAttempt attempt = requested();
        attempt.markPending("TEST-TX-1");

        assertThat(attempt.markUnknown()).isEqualTo(Result.IGNORED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @Test
    void sameFinalResultTwiceIsIgnored() {
        PaymentAttempt attempt = requested();
        attempt.fail("TEST-TX-1");

        assertThat(attempt.fail("TEST-TX-1")).isEqualTo(Result.IGNORED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
    }

    @Test
    void oppositeFinalResultIsConflictAndDoesNotOverwrite() {
        PaymentAttempt attempt = requested();
        attempt.succeed("TEST-TX-1");

        assertThat(attempt.fail("TEST-TX-1")).isEqualTo(Result.CONFLICT);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    @Test
    void differentTransactionKeyIsConflictAndKeepsStoredKey() {
        PaymentAttempt attempt = requested();
        attempt.markPending("TEST-TX-1");

        assertThat(attempt.succeed("TEST-TX-2")).isEqualTo(Result.CONFLICT);
        assertThat(attempt.markPending("TEST-TX-2")).isEqualTo(Result.CONFLICT);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getTransactionKey()).isEqualTo("TEST-TX-1");
    }

    @Test
    void rejectionBeforeCreateFailsRequestedAttemptWithoutTransactionKey() {
        PaymentAttempt attempt = requested();

        assertThat(attempt.reject()).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getTransactionKey()).isNull();
    }

    @Test
    void sameRejectionTwiceIsIgnored() {
        PaymentAttempt attempt = requested();
        attempt.reject();

        assertThat(attempt.reject()).isEqualTo(Result.IGNORED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
    }

    @Test
    void rejectionContradictingKnownProgressIsConflictAndKeepsState() {
        PaymentAttempt pending = requested();
        pending.markPending("TEST-TX-1");
        PaymentAttempt unknown = requested();
        unknown.markUnknown();
        PaymentAttempt failedWithTransaction = requested();
        failedWithTransaction.fail("TEST-TX-1");

        assertThat(pending.reject()).isEqualTo(Result.CONFLICT);
        assertThat(pending.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(unknown.reject()).isEqualTo(Result.CONFLICT);
        assertThat(unknown.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(failedWithTransaction.reject()).isEqualTo(Result.CONFLICT);
    }

    @Test
    void createThatNeverReachedPgFailsRequestedAttemptWithoutTransactionKey() {
        PaymentAttempt attempt = requested();

        assertThat(attempt.markNotSent()).isEqualTo(Result.APPLIED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(attempt.markNotSent()).isEqualTo(Result.IGNORED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"UNKNOWN", "PENDING", "SUCCESS", "FAILED"})
    void notSentContradictingKnownProgressIsConflictAndKeepsState(PaymentAttemptStatus status) {
        PaymentAttempt attempt = requested();
        switch (status) {
            case UNKNOWN -> attempt.markUnknown();
            case PENDING -> attempt.markPending("TEST-TX-1");
            case SUCCESS -> attempt.succeed("TEST-TX-1");
            case FAILED -> attempt.fail("TEST-TX-1");
            default -> throw new IllegalArgumentException("진행이 알려진 상태만 검증한다.");
        }

        assertThat(attempt.markNotSent()).isEqualTo(Result.CONFLICT);
        assertThat(attempt.getStatus()).isEqualTo(status);
        assertThat(attempt.getTransactionKey()).isEqualTo(status == PaymentAttemptStatus.UNKNOWN ? null : "TEST-TX-1");
    }

    @Test
    void rejectsInvalidAttemptAndMissingTransactionKey() {
        assertThatThrownBy(() -> PaymentAttempt.start(42L, " ", 3_000L, "TEST", "0000-0000-0000-0000"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentAttempt.start(42L, "attempt-abc", 0L, "TEST", "0000-0000-0000-0000"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentAttempt.start(42L, "attempt-abc", 3_000L, null, "0000-0000-0000-0000"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentAttempt.start(42L, "attempt-abc", 3_000L, "TEST", " "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested().succeed(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
