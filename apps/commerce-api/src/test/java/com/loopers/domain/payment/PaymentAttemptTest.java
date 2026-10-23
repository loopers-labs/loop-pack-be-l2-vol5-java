package com.loopers.domain.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class PaymentAttemptTest {
    private static final UUID ATTEMPT_KEY = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String PROVIDER_ACCOUNT = "135135";
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final String TRANSACTION_KEY = "TEST-TX-0042";
    private static final ZonedDateTime STARTED_AT = ZonedDateTime.parse("2026-10-23T00:00:00Z");

    @Test
    void startsWithoutAnExternalObservationOrDispatchRecord() {
        PaymentAttempt attempt = attempt();

        assertAll(
            () -> assertThat(attempt.getOrderId()).isEqualTo(42L),
            () -> assertThat(attempt.getAttemptKey()).isEqualTo(ATTEMPT_KEY),
            () -> assertThat(attempt.getProviderAccount()).isEqualTo(PROVIDER_ACCOUNT),
            () -> assertThat(attempt.getProviderOrderId()).isEqualTo(PROVIDER_ORDER_ID),
            () -> assertThat(attempt.getAmount()).isEqualTo(7000L),
            () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.NOT_OBSERVED),
            () -> assertThat(attempt.getSendStartedAt()).isNull(),
            () -> assertThat(attempt.getTransactionKey()).isNull()
        );
    }

    @Test
    void claimsDispatchOnceWithoutPretendingTheProviderAcceptedIt() {
        PaymentAttempt attempt = attempt();

        assertThat(attempt.startDispatch(STARTED_AT)).isTrue();
        assertThat(attempt.startDispatch(STARTED_AT.plusMinutes(1))).isFalse();

        assertAll(
            () -> assertThat(attempt.getSendStartedAt()).isEqualTo(STARTED_AT),
            () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.NOT_OBSERVED),
            () -> assertThat(attempt.getTransactionKey()).isNull()
        );
    }

    @Test
    void truncatesDispatchTimeToMicrosecondsAndPreservesTheFirstRecordOnDuplicate() {
        PaymentAttempt attempt = attempt();
        ZonedDateTime first = ZonedDateTime.parse("2026-10-23T12:34:56.123456789+09:00");
        ZonedDateTime expected = ZonedDateTime.parse("2026-10-23T12:34:56.123456+09:00");

        assertThat(attempt.startDispatch(first)).isTrue();
        assertThat(attempt.getSendStartedAt()).isEqualTo(expected);
        assertThat(attempt.getSendStartedAt().getNano()).isEqualTo(123456000);
        Snapshot afterFirstDispatch = snapshot(attempt);

        assertThat(attempt.startDispatch(first.plusMinutes(1))).isFalse();
        assertThat(snapshot(attempt)).isEqualTo(afterFirstDispatch);
    }

    @ParameterizedTest
    @CsvSource({
        "NOT_OBSERVED, PENDING, APPLIED", "NOT_OBSERVED, SUCCESS, APPLIED", "NOT_OBSERVED, FAILED, APPLIED",
        "PENDING, PENDING, UNCHANGED", "PENDING, SUCCESS, APPLIED", "PENDING, FAILED, APPLIED",
        "UNKNOWN, PENDING, APPLIED", "UNKNOWN, SUCCESS, APPLIED", "UNKNOWN, FAILED, APPLIED"
    })
    void appliesMatchingTransactionsFromEveryUnfinishedState(PaymentAttemptStatus current,
                                                             PaymentTransaction.Status observed,
                                                             PaymentObservationResult expected) {
        PaymentAttempt attempt = startedAttempt(current);

        assertThat(attempt.observe(transaction(observed))).isEqualTo(expected);

        assertAll(
            () -> assertThat(attempt.getStatus().name()).isEqualTo(observed.name()),
            () -> assertThat(attempt.getTransactionKey()).isEqualTo(TRANSACTION_KEY),
            () -> assertThat(attempt.getSendStartedAt()).isEqualTo(STARTED_AT),
            () -> assertThat(attempt.getAmount()).isEqualTo(7000L),
            () -> assertThat(attempt.getAttemptKey()).isEqualTo(ATTEMPT_KEY)
        );
    }

    @ParameterizedTest
    @CsvSource({"NOT_OBSERVED, APPLIED", "PENDING, APPLIED", "UNKNOWN, UNCHANGED"})
    void unknownDoesNotEraseDispatchOrAnAlreadyKnownTransaction(PaymentAttemptStatus current,
                                                               PaymentObservationResult expected) {
        PaymentAttempt attempt = startedAttempt(current);
        String previousTransactionKey = attempt.getTransactionKey();

        assertThat(attempt.markUnknown()).isEqualTo(expected);

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.getTransactionKey()).isEqualTo(previousTransactionKey);
        assertThat(attempt.getSendStartedAt()).isEqualTo(STARTED_AT);
        assertThat(attempt.startDispatch(STARTED_AT.plusMinutes(1))).isFalse();
    }

    @Test
    void recoversFromPendingThroughUnknownWithoutChangingTheTransaction() {
        PaymentAttempt attempt = startedAttempt(PaymentAttemptStatus.PENDING);

        assertThat(attempt.markUnknown()).isEqualTo(PaymentObservationResult.APPLIED);
        assertThat(attempt.observe(transaction(PaymentTransaction.Status.PENDING)))
            .isEqualTo(PaymentObservationResult.APPLIED);

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getTransactionKey()).isEqualTo(TRANSACTION_KEY);
        assertThat(attempt.getSendStartedAt()).isEqualTo(STARTED_AT);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"SUCCESS", "FAILED"})
    void preservesTerminalResultsForDuplicatesLatePendingAndUnknown(PaymentAttemptStatus terminal) {
        PaymentAttempt attempt = startedAttempt(terminal);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observe(transaction(PaymentTransaction.Status.valueOf(terminal.name()))))
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(attempt.observe(transaction(PaymentTransaction.Status.PENDING)))
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(attempt.markUnknown()).isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(attempt.startDispatch(STARTED_AT.plusMinutes(1))).isFalse();

        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS, FAILED", "FAILED, SUCCESS"})
    void rejectsContradictoryTerminalResults(PaymentAttemptStatus current, PaymentTransaction.Status opposite) {
        PaymentAttempt attempt = startedAttempt(current);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observe(transaction(opposite))).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("mismatchingTransactions")
    void rejectsMismatchesBeforeChangingStateOrBindingATransaction(PaymentAttemptStatus current,
                                                                  PaymentTransaction mismatch) {
        PaymentAttempt attempt = startedAttempt(current);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observe(mismatch)).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("mismatchingLatePendingTransactions")
    void checksIdentityEvenForLatePendingAfterATerminalResult(PaymentAttemptStatus terminal,
                                                             PaymentTransaction mismatch) {
        PaymentAttempt attempt = startedAttempt(terminal);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observe(mismatch)).isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"NOT_OBSERVED", "UNKNOWN"})
    void appliesNoEffectRejectionOnceWithoutInventingATransaction(PaymentAttemptStatus current) {
        PaymentAttempt attempt = startedAttempt(current);

        assertThat(attempt.observeRejectedBeforeCreate(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, 7000L))
            .isEqualTo(PaymentObservationResult.APPLIED);
        Snapshot after = snapshot(attempt);
        assertThat(attempt.observeRejectedBeforeCreate(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, 7000L))
            .isEqualTo(PaymentObservationResult.UNCHANGED);
        assertThat(attempt.markUnknown()).isEqualTo(PaymentObservationResult.UNCHANGED);

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(attempt.getSendStartedAt()).isEqualTo(STARTED_AT);
        assertThat(snapshot(attempt)).isEqualTo(after);
    }

    @ParameterizedTest
    @EnumSource(PaymentTransaction.Status.class)
    void treatsAnyTransactionAfterNoEffectRejectionAsAConflict(PaymentTransaction.Status status) {
        PaymentAttempt attempt = startedAttempt(PaymentAttemptStatus.NOT_OBSERVED);
        assertThat(attempt.observeRejectedBeforeCreate(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, 7000L))
            .isEqualTo(PaymentObservationResult.APPLIED);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observe(transaction(status))).isEqualTo(PaymentObservationResult.CONFLICT);

        assertThat(snapshot(attempt)).isEqualTo(before);
        assertThat(attempt.startDispatch(STARTED_AT.plusMinutes(1))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"PENDING", "UNKNOWN", "SUCCESS", "FAILED"})
    void rejectsNoEffectRejectionWhenATransactionIsAlreadyKnown(PaymentAttemptStatus current) {
        PaymentAttempt attempt = startedAttempt(current == PaymentAttemptStatus.UNKNOWN
            ? PaymentAttemptStatus.PENDING : current);
        if (current == PaymentAttemptStatus.UNKNOWN) {
            assertThat(attempt.markUnknown()).isEqualTo(PaymentObservationResult.APPLIED);
        }
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observeRejectedBeforeCreate(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, 7000L))
            .isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"other, LP-ORD-0000000042, 7000", "135135, LP-ORD-0000000099, 7000",
        "135135, LP-ORD-0000000042, 7001"})
    void rejectsNoEffectRejectionForADifferentRequest(String account, String order, long amount) {
        PaymentAttempt attempt = startedAttempt(PaymentAttemptStatus.NOT_OBSERVED);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observeRejectedBeforeCreate(account, order, amount))
            .isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"other, LP-ORD-0000000042, 7000", "135135, LP-ORD-0000000099, 7000",
        "135135, LP-ORD-0000000042, 7001"})
    void checksIdentityBeforeTreatingNoEffectRejectionAsADuplicate(String account, String order, long amount) {
        PaymentAttempt attempt = startedAttempt(PaymentAttemptStatus.NOT_OBSERVED);
        assertThat(attempt.observeRejectedBeforeCreate(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, 7000L))
            .isEqualTo(PaymentObservationResult.APPLIED);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observeRejectedBeforeCreate(account, order, amount))
            .isEqualTo(PaymentObservationResult.CONFLICT);
        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @Test
    void refusesEveryObservationBeforeDispatchAndPreservesAllFields() {
        PaymentAttempt attempt = attempt();
        Snapshot before = snapshot(attempt);

        for (PaymentTransaction.Status status : PaymentTransaction.Status.values()) {
            assertThatThrownBy(() -> attempt.observe(transaction(status))).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(attempt::markUnknown).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> attempt.observeRejectedBeforeCreate(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, 7000L))
            .isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @Test
    void unknownWithAKnownTransactionCannotBindADifferentTransaction() {
        PaymentAttempt attempt = startedAttempt(PaymentAttemptStatus.PENDING);
        assertThat(attempt.markUnknown()).isEqualTo(PaymentObservationResult.APPLIED);
        Snapshot before = snapshot(attempt);

        assertThat(attempt.observe(new PaymentTransaction(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, "OTHER-TX",
            7000L, PaymentTransaction.Status.SUCCESS))).isEqualTo(PaymentObservationResult.CONFLICT);

        assertThat(snapshot(attempt)).isEqualTo(before);
    }

    @Test
    void nullTimestampOrObservationDoesNotPartiallyChangeTheAttempt() {
        PaymentAttempt attempt = attempt();
        Snapshot initial = snapshot(attempt);
        assertThatThrownBy(() -> attempt.startDispatch(null)).isInstanceOf(NullPointerException.class);
        assertThat(snapshot(attempt)).isEqualTo(initial);

        assertThat(attempt.startDispatch(STARTED_AT)).isTrue();
        Snapshot started = snapshot(attempt);
        assertThatThrownBy(() -> attempt.startDispatch(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> attempt.observe(null)).isInstanceOf(NullPointerException.class);
        assertThat(snapshot(attempt)).isEqualTo(started);
    }

    private static Stream<Arguments> mismatchingLatePendingTransactions() {
        return Stream.of(PaymentAttemptStatus.SUCCESS, PaymentAttemptStatus.FAILED).flatMap(terminal -> Stream.of(
            new PaymentTransaction("other", PROVIDER_ORDER_ID, TRANSACTION_KEY, 7000L, PaymentTransaction.Status.PENDING),
            new PaymentTransaction(PROVIDER_ACCOUNT, "LP-ORD-0000000099", TRANSACTION_KEY, 7000L,
                PaymentTransaction.Status.PENDING),
            new PaymentTransaction(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, TRANSACTION_KEY, 7001L,
                PaymentTransaction.Status.PENDING),
            new PaymentTransaction(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, "OTHER-TX", 7000L,
                PaymentTransaction.Status.PENDING)
        ).map(mismatch -> Arguments.of(terminal, mismatch)));
    }

    private static Stream<Arguments> mismatchingTransactions() {
        return Stream.of(PaymentAttemptStatus.values()).flatMap(current -> {
            PaymentTransaction.Status status = current == PaymentAttemptStatus.FAILED
                ? PaymentTransaction.Status.FAILED : PaymentTransaction.Status.SUCCESS;
            Stream<PaymentTransaction> mismatches = Stream.of(
                new PaymentTransaction("other", PROVIDER_ORDER_ID, TRANSACTION_KEY, 7000L, status),
                new PaymentTransaction(PROVIDER_ACCOUNT, "LP-ORD-0000000099", TRANSACTION_KEY, 7000L, status),
                new PaymentTransaction(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, TRANSACTION_KEY, 7001L, status)
            );
            if (current == PaymentAttemptStatus.PENDING || current == PaymentAttemptStatus.SUCCESS
                || current == PaymentAttemptStatus.FAILED) {
                mismatches = Stream.concat(mismatches, Stream.of(
                    new PaymentTransaction(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, "OTHER-TX", 7000L, status)));
            }
            return mismatches.map(mismatch -> Arguments.of(current, mismatch));
        });
    }

    private PaymentAttempt startedAttempt(PaymentAttemptStatus current) {
        PaymentAttempt attempt = attempt();
        assertThat(attempt.startDispatch(STARTED_AT)).isTrue();
        if (current == PaymentAttemptStatus.UNKNOWN) {
            assertThat(attempt.markUnknown()).isEqualTo(PaymentObservationResult.APPLIED);
        } else if (current != PaymentAttemptStatus.NOT_OBSERVED) {
            assertThat(attempt.observe(transaction(PaymentTransaction.Status.valueOf(current.name()))))
                .isEqualTo(PaymentObservationResult.APPLIED);
        }
        assertThat(attempt.getStatus()).isEqualTo(current);
        return attempt;
    }

    private PaymentTransaction transaction(PaymentTransaction.Status status) {
        return new PaymentTransaction(PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, TRANSACTION_KEY, 7000L, status);
    }

    private Snapshot snapshot(PaymentAttempt attempt) {
        return new Snapshot(attempt.getOrderId(), attempt.getAttemptKey(), attempt.getProviderAccount(),
            attempt.getProviderOrderId(), attempt.getAmount(), attempt.getStatus(), attempt.getSendStartedAt(),
            attempt.getTransactionKey());
    }

    private record Snapshot(long orderId, UUID attemptKey, String account, String providerOrderId, long amount,
                            PaymentAttemptStatus status, ZonedDateTime startedAt, String transactionKey) {
    }

    private PaymentAttempt attempt() {
        return new PaymentAttempt(42L, ATTEMPT_KEY, PROVIDER_ACCOUNT, PROVIDER_ORDER_ID, 7000L);
    }
}
