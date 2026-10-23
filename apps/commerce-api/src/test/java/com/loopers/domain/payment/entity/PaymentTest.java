package com.loopers.domain.payment.entity;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.loopers.domain.payment.enums.PaymentConflict;
import com.loopers.domain.payment.enums.PaymentErrorCode;
import com.loopers.domain.payment.enums.PaymentStatus;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.domain.payment.enums.ReservationChange;
import com.loopers.domain.payment.enums.ReservationStatus;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;

/** 5주차 설계 2.2 전이표의 모든 칸 */
class PaymentTest {

    private static final Long ORDER_ID = 42L;
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final long AMOUNT = 7_000L;
    private static final String TX_KEY = "TEST-TX-0042";
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-19T10:00:00+09:00[Asia/Seoul]");

    private static Payment requested() {
        return Payment.request(ORDER_ID, "attempt-1", AMOUNT);
    }

    private static Payment unknown() {
        Payment payment = requested();
        payment.apply(new PaymentCreateResult.Unknown(), NOW);
        return payment;
    }

    private static Payment pending() {
        Payment payment = requested();
        payment.apply(new PaymentCreateResult.Accepted(TX_KEY), NOW);
        return payment;
    }

    private static Payment succeeded() {
        Payment payment = pending();
        payment.apply(found(tx(ProviderTransactionStatus.SUCCESS)), NOW);
        return payment;
    }

    private static Payment failed() {
        Payment payment = pending();
        payment.apply(found(tx(ProviderTransactionStatus.FAILED)), NOW);
        return payment;
    }

    private static Payment rejected() {
        Payment payment = requested();
        payment.apply(new PaymentCreateResult.RejectedWithoutEffect(), NOW);
        return payment;
    }

    private static Payment notRequired() {
        return Payment.notRequired(ORDER_ID, "attempt-1");
    }

    private static ProviderTransaction tx(ProviderTransactionStatus status) {
        return new ProviderTransaction(TX_KEY, PROVIDER_ORDER_ID, AMOUNT, status);
    }

    private static PaymentLookupResult found(ProviderTransaction... transactions) {
        return new PaymentLookupResult.Found(List.of(transactions));
    }

    /** 불일치는 복수 거래 · 금액 불일치 · 주문 불일치로 나눠 확인함 */
    private enum Lookup {
        ONE_PENDING(found(tx(ProviderTransactionStatus.PENDING))),
        ONE_SUCCESS(found(tx(ProviderTransactionStatus.SUCCESS))),
        ONE_FAILED(found(tx(ProviderTransactionStatus.FAILED))),
        NO_TRANSACTION(found()),
        MULTIPLE(found(tx(ProviderTransactionStatus.SUCCESS),
            new ProviderTransaction("TEST-TX-0043", PROVIDER_ORDER_ID, AMOUNT, ProviderTransactionStatus.SUCCESS))),
        AMOUNT_MISMATCH(found(new ProviderTransaction(TX_KEY, PROVIDER_ORDER_ID, AMOUNT + 1, ProviderTransactionStatus.SUCCESS))),
        ORDER_MISMATCH(found(new ProviderTransaction(TX_KEY, "LP-ORD-0000000043", AMOUNT, ProviderTransactionStatus.SUCCESS))),
        UNAVAILABLE(new PaymentLookupResult.Unavailable());

        private final PaymentLookupResult result;

        Lookup(PaymentLookupResult result) {
            this.result = result;
        }
    }

    private static Arguments row(Lookup lookup, PaymentStatus status, ReservationStatus reservation,
                                 ReservationChange change, PaymentConflict conflict) {
        return Arguments.of(lookup, status, reservation, change, conflict);
    }

    private static void assertLookupRow(Supplier<Payment> given, Lookup lookup, PaymentStatus status,
                                        ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
        // arrange
        Payment payment = given.get();

        // act
        ReservationChange result = payment.apply(lookup.result, NOW);

        // assert
        assertAll(
            () -> assertThat(payment.getStatus()).isEqualTo(status),
            () -> assertThat(payment.getReservationStatus()).isEqualTo(reservation),
            () -> assertThat(result).isEqualTo(change),
            () -> assertThat(payment.getConflictReason()).isEqualTo(conflict),
            () -> assertThat(payment.getConflictedAt()).isEqualTo(conflict == null ? null : NOW)
        );
    }

    @DisplayName("시도를 기록할 때, ")
    @Nested
    class Request {
        @DisplayName("REQUESTED · RESERVED 이고, 주문 단위 조회에 쓸 providerOrderId 는 LP-ORD- + 주문 ID 10자리다.")
        @Test
        void createsRequestedAndReserved() {
            // act
            Payment payment = requested();

            // assert
            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED),
                () -> assertThat(payment.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED),
                () -> assertThat(payment.getProviderOrderId()).isEqualTo(PROVIDER_ORDER_ID),
                () -> assertThat(payment.getTransactionKey()).isNull(),
                () -> assertThat(payment.getAmount()).isEqualTo(AMOUNT),
                () -> assertThat(payment.getConflictReason()).isNull()
            );
        }

        @DisplayName("0원 주문이면, 금액 0 · 거래 키 없이 NOT_REQUIRED · CONSUMED 로 종결된다. (D-68)")
        @Test
        void createsNotRequiredAndConsumed_forZeroAmountOrder() {
            // act
            Payment payment = notRequired();

            // assert
            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NOT_REQUIRED),
                () -> assertThat(payment.getStatus().isTerminal()).isTrue(),
                () -> assertThat(payment.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED),
                () -> assertThat(payment.getAmount()).isZero(),
                () -> assertThat(payment.getTransactionKey()).isNull(),
                () -> assertThat(payment.getProviderOrderId()).isEqualTo(PROVIDER_ORDER_ID)
            );
        }

        @DisplayName("시도 키가 비었으면, INVALID_PAYMENT_REQUEST 예외가 발생한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void throwsInvalidRequest_whenAttemptKeyIsBlank(String attemptKey) {
            CoreException result = assertThrows(CoreException.class, () -> Payment.request(ORDER_ID, attemptKey, AMOUNT));

            assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }

        @DisplayName("시도 키가 64자를 넘으면 INVALID_PAYMENT_REQUEST 예외가 발생하고, 64자는 허용한다.")
        @Test
        void throwsInvalidRequest_whenAttemptKeyIsTooLong() {
            CoreException result = assertThrows(CoreException.class, () -> Payment.request(ORDER_ID, "k".repeat(65), AMOUNT));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.INVALID_PAYMENT_REQUEST),
                () -> assertThat(Payment.request(ORDER_ID, "k".repeat(64), AMOUNT).getAttemptKey()).hasSize(64)
            );
        }

        @DisplayName("금액이 음수면, INVALID_PAYMENT_REQUEST 예외가 발생한다.")
        @Test
        void throwsInvalidRequest_whenAmountIsNegative() {
            CoreException result = assertThrows(CoreException.class, () -> Payment.request(ORDER_ID, "attempt-1", -1L));

            assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }

    @DisplayName("REQUESTED 에서, ")
    @Nested
    class FromRequested {
        @DisplayName("생성 수락이면, PENDING 이 되고 거래 키를 저장한다.")
        @Test
        void becomesPending_onAccepted() {
            Payment payment = requested();

            ReservationChange result = payment.apply(new PaymentCreateResult.Accepted(TX_KEY), NOW);

            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
                () -> assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY),
                () -> assertThat(payment.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED),
                () -> assertThat(result).isEqualTo(ReservationChange.NONE)
            );
        }

        @DisplayName("효과 0건 확정 거절이면, REJECTED 로 종결하고 예약 반환을 요구하며 거래 키는 꾸미지 않는다.")
        @Test
        void becomesRejected_onRejectedWithoutEffect() {
            Payment payment = requested();

            ReservationChange result = payment.apply(new PaymentCreateResult.RejectedWithoutEffect(), NOW);

            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED),
                () -> assertThat(payment.getReservationStatus()).isEqualTo(ReservationStatus.RELEASED),
                () -> assertThat(payment.getTransactionKey()).isNull(),
                () -> assertThat(result).isEqualTo(ReservationChange.RELEASE)
            );
        }

        @DisplayName("결과 미확인이면, UNKNOWN 이 되고 예약을 유지한다.")
        @Test
        void becomesUnknown_onUnknown() {
            Payment payment = requested();

            ReservationChange result = payment.apply(new PaymentCreateResult.Unknown(), NOW);

            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN),
                () -> assertThat(payment.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED),
                () -> assertThat(result).isEqualTo(ReservationChange.NONE)
            );
        }

        static Stream<Arguments> lookupRows() {
            return Stream.of(
                row(Lookup.ONE_PENDING, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, null),
                row(Lookup.ONE_SUCCESS, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.CONSUME, null),
                row(Lookup.ONE_FAILED, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.RELEASE, null),
                row(Lookup.NO_TRANSACTION, PaymentStatus.REQUESTED, ReservationStatus.RESERVED, ReservationChange.NONE, null),
                row(Lookup.MULTIPLE, PaymentStatus.REQUESTED, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MULTIPLE_TRANSACTIONS),
                row(Lookup.AMOUNT_MISMATCH, PaymentStatus.REQUESTED, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MISMATCHED_TRANSACTION),
                row(Lookup.ORDER_MISMATCH, PaymentStatus.REQUESTED, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MISMATCHED_TRANSACTION),
                row(Lookup.UNAVAILABLE, PaymentStatus.REQUESTED, ReservationStatus.RESERVED, ReservationChange.NONE, null)
            );
        }

        @DisplayName("조회 결과마다 전이표대로 바뀐다.")
        @ParameterizedTest(name = "{0} → {1}, {2}, {3}, 충돌 {4}")
        @MethodSource("lookupRows")
        void followsTable(Lookup lookup, PaymentStatus status, ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
            assertLookupRow(PaymentTest::requested, lookup, status, reservation, change, conflict);
        }

        @DisplayName("조회로 거래를 찾으면 그 거래 키를 저장한다. (발송 중에 reconcile · callback 이 먼저 온 경우)")
        @Test
        void storesTransactionKey_whenFoundByLookup() {
            Payment payment = requested();

            payment.apply(Lookup.ONE_PENDING.result, NOW);

            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
        }
    }

    @DisplayName("UNKNOWN 에서, ")
    @Nested
    class FromUnknown {
        static Stream<Arguments> lookupRows() {
            return Stream.of(
                row(Lookup.ONE_PENDING, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, null),
                row(Lookup.ONE_SUCCESS, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.CONSUME, null),
                row(Lookup.ONE_FAILED, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.RELEASE, null),
                row(Lookup.NO_TRANSACTION, PaymentStatus.UNKNOWN, ReservationStatus.RESERVED, ReservationChange.NONE, null),
                row(Lookup.MULTIPLE, PaymentStatus.UNKNOWN, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MULTIPLE_TRANSACTIONS),
                row(Lookup.AMOUNT_MISMATCH, PaymentStatus.UNKNOWN, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MISMATCHED_TRANSACTION),
                row(Lookup.ORDER_MISMATCH, PaymentStatus.UNKNOWN, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MISMATCHED_TRANSACTION),
                row(Lookup.UNAVAILABLE, PaymentStatus.UNKNOWN, ReservationStatus.RESERVED, ReservationChange.NONE, null)
            );
        }

        @DisplayName("조회 결과마다 전이표대로 바뀐다. 처리 중인 거래를 찾으면 아는 것이 늘었으므로 PENDING 으로 올린다.")
        @ParameterizedTest(name = "{0} → {1}, {2}, {3}, 충돌 {4}")
        @MethodSource("lookupRows")
        void followsTable(Lookup lookup, PaymentStatus status, ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
            assertLookupRow(PaymentTest::unknown, lookup, status, reservation, change, conflict);
        }

        @DisplayName("처리 중인 거래로 PENDING 이 되면, 조회한 거래 키를 저장한다.")
        @Test
        void storesTransactionKey_onPendingLookup() {
            Payment payment = unknown();

            payment.apply(Lookup.ONE_PENDING.result, NOW);

            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
        }
    }

    @DisplayName("PENDING 에서, ")
    @Nested
    class FromPending {
        static Stream<Arguments> lookupRows() {
            return Stream.of(
                row(Lookup.ONE_PENDING, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, null),
                row(Lookup.ONE_SUCCESS, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.CONSUME, null),
                row(Lookup.ONE_FAILED, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.RELEASE, null),
                row(Lookup.NO_TRANSACTION, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MISSING_KNOWN_TRANSACTION),
                row(Lookup.MULTIPLE, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MULTIPLE_TRANSACTIONS),
                row(Lookup.AMOUNT_MISMATCH, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MISMATCHED_TRANSACTION),
                row(Lookup.ORDER_MISMATCH, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, PaymentConflict.MISMATCHED_TRANSACTION),
                row(Lookup.UNAVAILABLE, PaymentStatus.PENDING, ReservationStatus.RESERVED, ReservationChange.NONE, null)
            );
        }

        @DisplayName("조회 결과마다 전이표대로 바뀐다. 거래 0건은 상태를 굳히지 않고 기록만 한다.")
        @ParameterizedTest(name = "{0} → {1}, {2}, {3}, 충돌 {4}")
        @MethodSource("lookupRows")
        void followsTable(Lookup lookup, PaymentStatus status, ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
            assertLookupRow(PaymentTest::pending, lookup, status, reservation, change, conflict);
        }

        @DisplayName("저장된 거래 키와 다른 거래 한 건이 조회되면, 불일치로 기록하고 종결하지 않는다.")
        @Test
        void recordsMismatch_whenTransactionKeyDiffers() {
            Payment payment = pending();

            ReservationChange result = payment.apply(
                found(new ProviderTransaction("TEST-TX-0043", PROVIDER_ORDER_ID, AMOUNT, ProviderTransactionStatus.SUCCESS)), NOW);

            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
                () -> assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY),
                () -> assertThat(payment.getConflictReason()).isEqualTo(PaymentConflict.MISMATCHED_TRANSACTION),
                () -> assertThat(result).isEqualTo(ReservationChange.NONE)
            );
        }

        @DisplayName("같은 거래 키의 생성 수락이 늦게 오면, 아무것도 바뀌지 않는다.")
        @Test
        void ignoresLateAcceptedWithSameKey() {
            Payment payment = pending();

            ReservationChange result = payment.apply(new PaymentCreateResult.Accepted(TX_KEY), NOW);

            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
                () -> assertThat(payment.getConflictReason()).isNull(),
                () -> assertThat(result).isEqualTo(ReservationChange.NONE)
            );
        }

        @DisplayName("다른 거래 키의 생성 수락이나 효과 0건 거절이 늦게 오면, 상태와 키는 그대로 두고 모순을 기록한다.")
        @Test
        void recordsContradiction_onLateCreateMismatch() {
            Payment differentKey = pending();
            Payment rejected = pending();

            ReservationChange differentKeyResult = differentKey.apply(new PaymentCreateResult.Accepted("TEST-TX-0043"), NOW);
            ReservationChange rejectedResult = rejected.apply(new PaymentCreateResult.RejectedWithoutEffect(), NOW);

            assertAll(
                () -> assertThat(differentKey.getStatus()).isEqualTo(PaymentStatus.PENDING),
                () -> assertThat(differentKey.getTransactionKey()).isEqualTo(TX_KEY),
                () -> assertThat(differentKey.getConflictReason()).isEqualTo(PaymentConflict.LATE_CREATE_CONTRADICTION),
                () -> assertThat(differentKeyResult).isEqualTo(ReservationChange.NONE),
                () -> assertThat(rejected.getStatus()).isEqualTo(PaymentStatus.PENDING),
                () -> assertThat(rejected.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED),
                () -> assertThat(rejected.getConflictReason()).isEqualTo(PaymentConflict.LATE_CREATE_CONTRADICTION),
                () -> assertThat(rejectedResult).isEqualTo(ReservationChange.NONE)
            );
        }

        @DisplayName("결과 미확인이 늦게 오면, UNKNOWN 으로 낮추지 않는다.")
        @Test
        void keepsPending_onLateUnknown() {
            Payment payment = pending();

            payment.apply(new PaymentCreateResult.Unknown(), NOW);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }

    @DisplayName("종결 상태에서, ")
    @Nested
    class FromTerminal {
        static Stream<Arguments> succeededRows() {
            return Stream.of(
                row(Lookup.ONE_PENDING, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.NONE, null),
                row(Lookup.ONE_SUCCESS, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.NONE, null),
                row(Lookup.ONE_FAILED, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.NO_TRANSACTION, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.NONE, null),
                row(Lookup.MULTIPLE, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.AMOUNT_MISMATCH, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.UNAVAILABLE, PaymentStatus.SUCCESS, ReservationStatus.CONSUMED, ReservationChange.NONE, null)
            );
        }

        @DisplayName("SUCCESS 는 덮어쓰지 않고, 같은 결과에는 효과가 없으며 상충하는 결과만 기록한다.")
        @ParameterizedTest(name = "{0} → {1}, {2}, {3}, 충돌 {4}")
        @MethodSource("succeededRows")
        void succeededFollowsTable(Lookup lookup, PaymentStatus status, ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
            assertLookupRow(PaymentTest::succeeded, lookup, status, reservation, change, conflict);
        }

        static Stream<Arguments> failedRows() {
            return Stream.of(
                row(Lookup.ONE_PENDING, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.NONE, null),
                row(Lookup.ONE_SUCCESS, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.ONE_FAILED, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.NONE, null),
                row(Lookup.NO_TRANSACTION, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.NONE, null),
                row(Lookup.MULTIPLE, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.ORDER_MISMATCH, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.UNAVAILABLE, PaymentStatus.FAILED, ReservationStatus.RELEASED, ReservationChange.NONE, null)
            );
        }

        @DisplayName("FAILED 는 덮어쓰지 않고, 같은 결과를 반복해도 반환을 다시 요구하지 않는다.")
        @ParameterizedTest(name = "{0} → {1}, {2}, {3}, 충돌 {4}")
        @MethodSource("failedRows")
        void failedFollowsTable(Lookup lookup, PaymentStatus status, ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
            assertLookupRow(PaymentTest::failed, lookup, status, reservation, change, conflict);
        }

        static Stream<Arguments> rejectedRows() {
            return Stream.of(
                row(Lookup.ONE_PENDING, PaymentStatus.REJECTED, ReservationStatus.RELEASED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.ONE_SUCCESS, PaymentStatus.REJECTED, ReservationStatus.RELEASED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.ONE_FAILED, PaymentStatus.REJECTED, ReservationStatus.RELEASED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.NO_TRANSACTION, PaymentStatus.REJECTED, ReservationStatus.RELEASED, ReservationChange.NONE, null),
                row(Lookup.MULTIPLE, PaymentStatus.REJECTED, ReservationStatus.RELEASED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.UNAVAILABLE, PaymentStatus.REJECTED, ReservationStatus.RELEASED, ReservationChange.NONE, null)
            );
        }

        @DisplayName("REJECTED 에서 거래가 발견되면, 상태는 그대로 두고 모순을 기록한다.")
        @ParameterizedTest(name = "{0} → {1}, {2}, {3}, 충돌 {4}")
        @MethodSource("rejectedRows")
        void rejectedFollowsTable(Lookup lookup, PaymentStatus status, ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
            assertLookupRow(PaymentTest::rejected, lookup, status, reservation, change, conflict);
        }

        static Stream<Arguments> notRequiredRows() {
            return Stream.of(
                row(Lookup.ONE_PENDING, PaymentStatus.NOT_REQUIRED, ReservationStatus.CONSUMED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.ONE_SUCCESS, PaymentStatus.NOT_REQUIRED, ReservationStatus.CONSUMED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.ONE_FAILED, PaymentStatus.NOT_REQUIRED, ReservationStatus.CONSUMED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.NO_TRANSACTION, PaymentStatus.NOT_REQUIRED, ReservationStatus.CONSUMED, ReservationChange.NONE, null),
                row(Lookup.MULTIPLE, PaymentStatus.NOT_REQUIRED, ReservationStatus.CONSUMED, ReservationChange.NONE, PaymentConflict.TERMINAL_CONTRADICTION),
                row(Lookup.UNAVAILABLE, PaymentStatus.NOT_REQUIRED, ReservationStatus.CONSUMED, ReservationChange.NONE, null)
            );
        }

        @DisplayName("NOT_REQUIRED(0원 주문)에서 거래가 발견되면, 상태는 그대로 두고 모순을 기록한다. (D-68)")
        @ParameterizedTest(name = "{0} → {1}, {2}, {3}, 충돌 {4}")
        @MethodSource("notRequiredRows")
        void notRequiredFollowsTable(Lookup lookup, PaymentStatus status, ReservationStatus reservation, ReservationChange change, PaymentConflict conflict) {
            assertLookupRow(PaymentTest::notRequired, lookup, status, reservation, change, conflict);
        }

        static Stream<Arguments> terminalPayments() {
            return Stream.of(
                Arguments.of("SUCCESS", (Supplier<Payment>) PaymentTest::succeeded),
                Arguments.of("FAILED", (Supplier<Payment>) PaymentTest::failed),
                Arguments.of("REJECTED", (Supplier<Payment>) PaymentTest::rejected),
                Arguments.of("NOT_REQUIRED", (Supplier<Payment>) PaymentTest::notRequired)
            );
        }

        @DisplayName("늦게 도착한 생성 응답은 상태 · 예약 · 충돌 기록을 바꾸지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("terminalPayments")
        void ignoresLateCreateResults(String name, Supplier<Payment> given) {
            Payment payment = given.get();
            PaymentStatus status = payment.getStatus();
            ReservationStatus reservation = payment.getReservationStatus();

            List<ReservationChange> results = Stream.of(
                    new PaymentCreateResult.Accepted("TEST-TX-0099"),
                    new PaymentCreateResult.RejectedWithoutEffect(),
                    new PaymentCreateResult.Unknown())
                .map(result -> payment.apply(result, NOW))
                .toList();

            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(status),
                () -> assertThat(payment.getReservationStatus()).isEqualTo(reservation),
                () -> assertThat(payment.getConflictReason()).isNull(),
                () -> assertThat(results).containsOnly(ReservationChange.NONE)
            );
        }
    }

    @DisplayName("같은 종결 사건을 반복해도, 확정 · 반환은 처음 한 번만 요구한다. (중복 callback · 동시 reconcile)")
    @Test
    void requestsSettlementOnlyOnce() {
        Payment success = pending();
        Payment failure = pending();
        Payment rejection = requested();

        List<ReservationChange> successResults = repeat(3, () -> success.apply(Lookup.ONE_SUCCESS.result, NOW));
        List<ReservationChange> failureResults = repeat(3, () -> failure.apply(Lookup.ONE_FAILED.result, NOW));
        List<ReservationChange> rejectionResults = repeat(3, () -> rejection.apply(new PaymentCreateResult.RejectedWithoutEffect(), NOW));

        assertAll(
            () -> assertThat(successResults).containsExactly(ReservationChange.CONSUME, ReservationChange.NONE, ReservationChange.NONE),
            () -> assertThat(failureResults).containsExactly(ReservationChange.RELEASE, ReservationChange.NONE, ReservationChange.NONE),
            () -> assertThat(rejectionResults).containsExactly(ReservationChange.RELEASE, ReservationChange.NONE, ReservationChange.NONE)
        );
    }

    @DisplayName("복수 거래로 충돌을 기록한 뒤에도 같은 주문의 거래가 계속 2건이면, 확정되지 않는다. (D-66)")
    @Test
    void neverSettles_whileMultipleTransactionsRemain() {
        Payment payment = pending();

        payment.apply(Lookup.MULTIPLE.result, NOW);
        ReservationChange result = payment.apply(Lookup.MULTIPLE.result, NOW.plusMinutes(1));

        assertAll(
            () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
            () -> assertThat(payment.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED),
            () -> assertThat(payment.getConflictedAt()).isEqualTo(NOW.plusMinutes(1)),
            () -> assertThat(result).isEqualTo(ReservationChange.NONE)
        );
    }

    @DisplayName("충돌을 기록했어도, 다음 조회가 정상 한 건이면 전이표대로 종결한다. (D-65)")
    @Test
    void settlesAfterTransientAnomaly() {
        Payment payment = pending();
        payment.apply(Lookup.NO_TRANSACTION.result, NOW);

        ReservationChange result = payment.apply(Lookup.ONE_SUCCESS.result, NOW);

        assertAll(
            () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
            () -> assertThat(payment.getConflictReason()).isEqualTo(PaymentConflict.MISSING_KNOWN_TRANSACTION),
            () -> assertThat(result).isEqualTo(ReservationChange.CONSUME)
        );
    }

    private static List<ReservationChange> repeat(int times, Supplier<ReservationChange> action) {
        return Stream.generate(action).limit(times).toList();
    }
}
