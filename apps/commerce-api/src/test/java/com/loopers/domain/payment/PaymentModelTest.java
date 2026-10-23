package com.loopers.domain.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.ObservationDecision.Effect;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * PaymentModel.recordObservation 의 판정(설계 3.6 확인 정보의 반영 순서와 종결 처리, 4.3 applyObservation).
 * 저장 전 엔티티의 ID 는 0 이므로 토큰도 0 을 쓴다.
 */
@DisplayName("Payment 는 관찰을 복수 거래 → 식별 → 금액 → 종결 순서로 판정하고 결제·확인 정보만 바꾼다.")
class PaymentModelTest {

    private static final long ORDER_ID = 42L;
    private static final String ORDER = "LP-ORD-0000000042";
    private static final String KA = "tx-A";
    private static final String KB = "tx-B";
    private static final ZonedDateTime CLAIMED = ZonedDateTime.parse("2026-10-23T00:00:00Z");
    private static final ZonedDateTime T1 = CLAIMED.plusSeconds(1);
    private static final ZonedDateTime T2 = CLAIMED.plusSeconds(2);

    private static PaymentModel payment() {
        return PaymentModel.start(ORDER_ID, "123e4567-e89b-12d3-a456-426614174000", "135135", Money.of(7_000L), CLAIMED);
    }

    private static PaymentCheckToken create() {
        return new PaymentCheckToken(0L, ORDER_ID, ObservationSource.CREATE, false);
    }

    private static PaymentCheckToken query(boolean terminalAtStart) {
        return new PaymentCheckToken(0L, ORDER_ID, ObservationSource.QUERY, terminalAtStart);
    }

    private static PaymentObservation.TransactionObserved tx(ObservationSource source, String key, String order, long amount,
                                                             PgTransactionStatus status) {
        return new PaymentObservation.TransactionObserved(source, key, order, amount, status);
    }

    private static PaymentObservation.TransactionObserved queried(String key, PgTransactionStatus status) {
        return tx(ObservationSource.QUERY, key, ORDER, 7_000L, status);
    }

    /** CREATE PENDING 으로 KA 를 결합한 미종결 결제. */
    private static PaymentModel pendingWithKa() {
        PaymentModel payment = payment();
        payment.recordObservation(create(), tx(ObservationSource.CREATE, KA, ORDER, 7_000L, PgTransactionStatus.PENDING), T1);
        return payment;
    }

    private static PaymentModel terminal(PgTransactionStatus status) {
        PaymentModel payment = pendingWithKa();
        payment.recordObservation(query(false), queried(KA, status), T1);
        return payment;
    }

    @DisplayName("시작할 때,")
    @Nested
    class Start {

        @DisplayName("UNKNOWN·NOT_CHECKED·키 없음이며 외부 주문 ID 는 LP-ORD- + 최소 10자리, 시도 키는 소문자 UUID 다.")
        @Test
        void initialState() {
            PaymentModel payment = PaymentModel.start(ORDER_ID, "123E4567-E89B-12D3-A456-426614174000", "135135",
                Money.of(7_000L), CLAIMED);

            assertAll(
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.NOT_CHECKED),
                () -> assertThat(payment.getVerificationCheckedAt()).isNull(),
                () -> assertThat(payment.getTransactionKey()).isNull(),
                () -> assertThat(payment.getProvider()).isEqualTo("TEST_PG"),
                () -> assertThat(payment.getProviderOrderId()).isEqualTo(ORDER),
                () -> assertThat(payment.getAttemptKey()).isEqualTo("123e4567-e89b-12d3-a456-426614174000"),
                () -> assertThat(PaymentModel.providerOrderIdOf(12_345_678_901L)).as("자르지 않음")
                    .isEqualTo("LP-ORD-12345678901")
            );
        }

        @DisplayName("UUID 가 아닌 시도 키는 INVALID_REQUEST, 0 이하 금액은 내부 오류다.")
        @Test
        void rejectsInvalidStart() {
            assertAll(
                () -> assertThatThrownBy(() -> PaymentModel.start(ORDER_ID, "not-a-uuid", "135135", Money.of(1L), CLAIMED))
                    .extracting("errorType").isEqualTo(ErrorType.INVALID_REQUEST),
                () -> assertThatThrownBy(() -> PaymentModel.start(ORDER_ID, "1-1-1-1-1", "135135", Money.of(1L), CLAIMED))
                    .as("비표준 축약 UUID").extracting("errorType").isEqualTo(ErrorType.INVALID_REQUEST),
                () -> assertThatThrownBy(() -> PaymentModel.start(ORDER_ID, "123e4567-e89b-12d3-a456-426614174000",
                    "135135", Money.of(0L), CLAIMED)).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
            );
        }
    }

    @DisplayName("미종결 결제에 거래를 반영할 때,")
    @Nested
    class Unterminated {

        @DisplayName("PENDING 은 키를 결합하고 PENDING·VERIFIED 다.")
        @Test
        void pending() {
            PaymentModel payment = payment();

            ObservationDecision decision = payment.recordObservation(create(),
                tx(ObservationSource.CREATE, KA, ORDER, 7_000L, PgTransactionStatus.PENDING), T1);

            assertAll(
                () -> assertThat(decision.effect()).isEqualTo(Effect.PENDING),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
                () -> assertThat(payment.getTransactionKey()).isEqualTo(KA),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED),
                () -> assertThat(payment.getVerificationCheckedAt()).isEqualTo(T1)
            );
        }

        @DisplayName("SUCCESS 는 CONFIRM, FAILED 는 PG_FAILED 로 RELEASE 다.")
        @Test
        void terminalResults() {
            PaymentModel success = pendingWithKa();
            PaymentModel failed = pendingWithKa();

            ObservationDecision confirmed = success.recordObservation(query(false), queried(KA, PgTransactionStatus.SUCCESS), T2);
            ObservationDecision released = failed.recordObservation(query(false), queried(KA, PgTransactionStatus.FAILED), T2);

            assertAll(
                () -> assertThat(confirmed.effect()).isEqualTo(Effect.CONFIRM),
                () -> assertThat(success.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
                () -> assertThat(released.effect()).isEqualTo(Effect.RELEASE),
                () -> assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED),
                () -> assertThat(failed.getFailureKind()).isEqualTo(PaymentFailureKind.PG_FAILED)
            );
        }

        @DisplayName("생성 즉시 SUCCESS 도 미결합 키를 결합하고 CONFIRM 이다.")
        @Test
        void immediateSuccess() {
            PaymentModel payment = payment();

            ObservationDecision decision = payment.recordObservation(create(),
                tx(ObservationSource.CREATE, KA, ORDER, 7_000L, PgTransactionStatus.SUCCESS), T1);

            assertAll(
                () -> assertThat(decision.effect()).isEqualTo(Effect.CONFIRM),
                () -> assertThat(payment.getTransactionKey()).isEqualTo(KA)
            );
        }

        @DisplayName("확인 불가는 업무 상태를 유지하고 확인 정보만 UNAVAILABLE 이다.")
        @Test
        void unavailable() {
            PaymentModel payment = payment();

            ObservationDecision decision = payment.recordObservation(create(),
                new PaymentObservation.Unavailable(ObservationSource.CREATE), T1);

            assertAll(
                () -> assertThat(decision.effect()).isEqualTo(Effect.UNAVAILABLE),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.UNAVAILABLE)
            );
        }

        @DisplayName("정확한 생성 전 거절은 키 없이 REJECTED_NO_EFFECT 로 RELEASE·VERIFIED 다.")
        @Test
        void rejectedNoEffect() {
            PaymentModel payment = payment();

            ObservationDecision decision = payment.recordObservation(create(),
                new PaymentObservation.RejectedNoEffect(ORDER, 7_000L), T1);

            assertAll(
                () -> assertThat(decision.effect()).isEqualTo(Effect.RELEASE),
                () -> assertThat(payment.getFailureKind()).isEqualTo(PaymentFailureKind.REJECTED_NO_EFFECT),
                () -> assertThat(payment.getTransactionKey()).isNull(),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED)
            );
        }

        @DisplayName("로컬 미발송은 원인을 실패 종류로 남기고 RELEASE 하되 PG 확인이 아니라 확인 정보는 그대로다.")
        @ParameterizedTest
        @EnumSource(LocalNotDispatchedReason.class)
        void localNotDispatched(LocalNotDispatchedReason reason) {
            PaymentModel payment = payment();

            ObservationDecision decision = payment.recordObservation(create(),
                new PaymentObservation.LocalNotDispatched(reason), T1);

            assertAll(
                () -> assertThat(decision.effect()).isEqualTo(Effect.RELEASE),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED),
                () -> assertThat(payment.getFailureKind().name()).isEqualTo(reason.name()),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.NOT_CHECKED),
                () -> assertThat(payment.getVerificationCheckedAt()).isNull()
            );
        }
    }

    @DisplayName("충돌 코드를 정할 때,")
    @Nested
    class ConflictOrder {

        @DisplayName("다른 외부 주문 ID 면 금액도 달라도 IDENTITY_MISMATCH 이고 관찰 금액을 근거로 남긴다.")
        @Test
        void identityBeforeAmount() {
            PaymentModel payment = payment();

            ObservationDecision decision = payment.recordObservation(query(false),
                tx(ObservationSource.QUERY, KB, "LP-ORD-0000000099", 1L, PgTransactionStatus.SUCCESS), T1);

            assertAll(
                () -> assertThat(decision).isEqualTo(ObservationDecision.conflict(PaymentConflictCode.IDENTITY_MISMATCH,
                    new ConflictEvidence("LP-ORD-0000000099", KB, 1L, PgTransactionStatus.SUCCESS, null))),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN),
                () -> assertThat(payment.getTransactionKey()).isNull(),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.CONFLICT)
            );
        }

        @DisplayName("저장 키 KA 와 다른 KB 는 상태가 반대여도 종결 비교 전에 IDENTITY_MISMATCH 이고 KA 를 바꾸지 않는다.")
        @Test
        void otherKeyBeforeTerminal() {
            PaymentModel payment = terminal(PgTransactionStatus.SUCCESS);

            ObservationDecision decision = payment.recordObservation(query(true), queried(KB, PgTransactionStatus.FAILED), T2);

            assertAll(
                () -> assertThat(decision.conflictCode()).isEqualTo(PaymentConflictCode.IDENTITY_MISMATCH),
                () -> assertThat(payment.getTransactionKey()).isEqualTo(KA),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS)
            );
        }

        @DisplayName("식별이 같고 금액만 다르면 AMOUNT_MISMATCH 다.")
        @Test
        void amountOnly() {
            PaymentModel payment = pendingWithKa();

            ObservationDecision decision = payment.recordObservation(query(false),
                tx(ObservationSource.QUERY, KA, ORDER, 7_001L, PgTransactionStatus.SUCCESS), T2);

            assertAll(
                () -> assertThat(decision.conflictCode()).isEqualTo(PaymentConflictCode.AMOUNT_MISMATCH),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING)
            );
        }

        @DisplayName("adapter 의 AMOUNT_MISMATCH 라도 관찰 키가 저장 키와 다르면 IDENTITY_MISMATCH 로 올린다.")
        @Test
        void raisesAdapterAmountToIdentity() {
            PaymentModel payment = pendingWithKa();
            ConflictEvidence otherKey = new ConflictEvidence(ORDER, KB, 1L, PgTransactionStatus.PENDING, null);
            ConflictEvidence sameKey = new ConflictEvidence(ORDER, KA, 1L, PgTransactionStatus.PENDING, null);

            ObservationDecision raised = payment.recordObservation(create(),
                new PaymentObservation.Conflict(ObservationSource.CREATE, PaymentConflictCode.AMOUNT_MISMATCH, otherKey), T2);
            ObservationDecision kept = payment.recordObservation(create(),
                new PaymentObservation.Conflict(ObservationSource.CREATE, PaymentConflictCode.AMOUNT_MISMATCH, sameKey), T2);

            assertAll(
                () -> assertThat(raised.conflictCode()).isEqualTo(PaymentConflictCode.IDENTITY_MISMATCH),
                () -> assertThat(kept.conflictCode()).isEqualTo(PaymentConflictCode.AMOUNT_MISMATCH)
            );
        }

        @DisplayName("adapter 의 MULTIPLE_TRANSACTIONS·IDENTITY_MISMATCH 는 그대로 둔다.")
        @Test
        void keepsEarlierAdapterCodes() {
            PaymentModel payment = pendingWithKa();

            ObservationDecision multiple = payment.recordObservation(query(false), new PaymentObservation.Conflict(
                ObservationSource.QUERY, PaymentConflictCode.MULTIPLE_TRANSACTIONS, ConflictEvidence.ofCount(2)), T2);
            ObservationDecision identity = payment.recordObservation(query(false), new PaymentObservation.Conflict(
                ObservationSource.QUERY, PaymentConflictCode.IDENTITY_MISMATCH,
                new ConflictEvidence(ORDER, KA, 7_000L, PgTransactionStatus.SUCCESS, null)), T2);

            assertAll(
                () -> assertThat(multiple).isEqualTo(ObservationDecision.conflict(
                    PaymentConflictCode.MULTIPLE_TRANSACTIONS, ConflictEvidence.ofCount(2))),
                () -> assertThat(identity.conflictCode()).isEqualTo(PaymentConflictCode.IDENTITY_MISMATCH),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING)
            );
        }
    }

    @DisplayName("종결 뒤 관찰을 반영할 때,")
    @Nested
    class AfterTerminal {

        @DisplayName("같은 종결은 추가 효과 없이 VERIFIED·새 확인 시각이다.")
        @Test
        void sameTerminal() {
            PaymentModel payment = terminal(PgTransactionStatus.SUCCESS);

            ObservationDecision decision = payment.recordObservation(query(true), queried(KA, PgTransactionStatus.SUCCESS), T2);

            assertAll(
                () -> assertThat(decision.effect()).isEqualTo(Effect.FINAL_UNCHANGED),
                () -> assertThat(payment.getVerificationCheckedAt()).isEqualTo(T2)
            );
        }

        @DisplayName("반대 종결은 기존 종결을 유지하고 TERMINAL_MISMATCH 다.")
        @Test
        void oppositeTerminal() {
            PaymentModel payment = terminal(PgTransactionStatus.SUCCESS);

            ObservationDecision decision = payment.recordObservation(query(true), queried(KA, PgTransactionStatus.FAILED), T2);

            assertAll(
                () -> assertThat(decision.conflictCode()).isEqualTo(PaymentConflictCode.TERMINAL_MISMATCH),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.CONFLICT)
            );
        }

        @DisplayName("QUERY PENDING 은 종결 전에 준비했으면 무시하고, 종결 뒤 준비했으면 TERMINAL_REGRESSION 이다.")
        @Test
        void queryPending() {
            PaymentModel ignored = terminal(PgTransactionStatus.FAILED);
            PaymentModel regressed = terminal(PgTransactionStatus.FAILED);

            ObservationDecision late = ignored.recordObservation(query(false), queried(KA, PgTransactionStatus.PENDING), T2);
            ObservationDecision regression = regressed.recordObservation(query(true), queried(KA, PgTransactionStatus.PENDING), T2);

            assertAll(
                () -> assertThat(late.effect()).isEqualTo(Effect.FINAL_UNCHANGED),
                () -> assertThat(ignored.getVerificationCheckedAt()).as("무시는 확인 정보도 그대로").isEqualTo(T1),
                () -> assertThat(regression.conflictCode()).isEqualTo(PaymentConflictCode.TERMINAL_REGRESSION),
                () -> assertThat(regressed.getStatus()).isEqualTo(PaymentStatus.FAILED)
            );
        }

        @DisplayName("같은 거래의 늦은 CREATE PENDING 과 CREATE 확인 불가는 업무·확인 정보를 바꾸지 않는다.")
        @Test
        void lateCreateObservations() {
            PaymentModel payment = terminal(PgTransactionStatus.SUCCESS);

            ObservationDecision latePending = payment.recordObservation(create(),
                tx(ObservationSource.CREATE, KA, ORDER, 7_000L, PgTransactionStatus.PENDING), T2);
            ObservationDecision lateUnavailable = payment.recordObservation(create(),
                new PaymentObservation.Unavailable(ObservationSource.CREATE), T2);

            assertAll(
                () -> assertThat(latePending.effect()).isEqualTo(Effect.FINAL_UNCHANGED),
                () -> assertThat(lateUnavailable.effect()).isEqualTo(Effect.FINAL_UNCHANGED),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED),
                () -> assertThat(payment.getVerificationCheckedAt()).isEqualTo(T1)
            );
        }

        @DisplayName("종결 뒤 QUERY 확인 불가는 종결을 유지하고 확인 정보만 UNAVAILABLE 이다.")
        @Test
        void queryUnavailable() {
            PaymentModel payment = terminal(PgTransactionStatus.SUCCESS);

            ObservationDecision decision = payment.recordObservation(query(true),
                new PaymentObservation.Unavailable(ObservationSource.QUERY), T2);

            assertAll(
                () -> assertThat(decision.effect()).isEqualTo(Effect.UNAVAILABLE),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.UNAVAILABLE)
            );
        }
    }

    @DisplayName("효과 0건·로컬 미발송과 거래가 함께 보일 때,")
    @Nested
    class NoEffectContradiction {

        @DisplayName("같은 원인의 미발송 재적용은 추가 효과가 없고, 다른 원인이면 확인 정보를 바꾸지 않은 TERMINAL_MISMATCH 다.")
        @Test
        void repeatedNotDispatched() {
            PaymentModel payment = payment();
            payment.recordObservation(create(), new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED), T1);

            ObservationDecision same = payment.recordObservation(create(),
                new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED), T2);
            ObservationDecision other = payment.recordObservation(create(),
                new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.POOL_ACQUIRE_TIMEOUT), T2);

            assertAll(
                () -> assertThat(same.effect()).isEqualTo(Effect.FINAL_UNCHANGED),
                () -> assertThat(other).isEqualTo(ObservationDecision.conflict(
                    PaymentConflictCode.TERMINAL_MISMATCH, ConflictEvidence.none())),
                () -> assertThat(payment.getFailureKind()).isEqualTo(PaymentFailureKind.CIRCUIT_REJECTED),
                () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.NOT_CHECKED)
            );
        }

        @DisplayName("미발송 실패 뒤 유효 거래가 보이면 TERMINAL_MISMATCH, 거래가 결합된 결제의 미발송 관찰도 TERMINAL_MISMATCH 다.")
        @Test
        void notDispatchedWithTransaction() {
            PaymentModel notDispatched = payment();
            notDispatched.recordObservation(create(),
                new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.POOL_ACQUIRE_TIMEOUT), T1);
            PaymentModel bound = pendingWithKa();

            ObservationDecision transactionFound = notDispatched.recordObservation(query(true),
                queried(KA, PgTransactionStatus.SUCCESS), T2);
            ObservationDecision notDispatchedAfterBind = bound.recordObservation(create(),
                new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED), T2);

            assertAll(
                () -> assertThat(transactionFound.conflictCode()).isEqualTo(PaymentConflictCode.TERMINAL_MISMATCH),
                () -> assertThat(notDispatched.getTransactionKey()).isNull(),
                () -> assertThat(notDispatchedAfterBind.conflictCode()).isEqualTo(PaymentConflictCode.TERMINAL_MISMATCH),
                () -> assertThat(bound.getStatus()).isEqualTo(PaymentStatus.PENDING)
            );
        }

        @DisplayName("효과 0건 거절의 재적용은 추가 효과가 없고, 거래 결합 결제의 거절 관찰은 TERMINAL_MISMATCH 다.")
        @Test
        void rejectedNoEffectAgain() {
            PaymentModel rejected = payment();
            rejected.recordObservation(create(), new PaymentObservation.RejectedNoEffect(ORDER, 7_000L), T1);
            PaymentModel failed = terminal(PgTransactionStatus.FAILED);

            ObservationDecision again = rejected.recordObservation(create(),
                new PaymentObservation.RejectedNoEffect(ORDER, 7_000L), T2);
            ObservationDecision withTransaction = failed.recordObservation(create(),
                new PaymentObservation.RejectedNoEffect(ORDER, 7_000L), T2);

            assertAll(
                () -> assertThat(again.effect()).isEqualTo(Effect.FINAL_UNCHANGED),
                () -> assertThat(withTransaction.conflictCode()).isEqualTo(PaymentConflictCode.TERMINAL_MISMATCH),
                () -> assertThat(failed.getFailureKind()).isEqualTo(PaymentFailureKind.PG_FAILED)
            );
        }
    }

    @DisplayName("다른 결제에 결합된 거래는 업무 상태·저장 키를 그대로 두고 TRANSACTION_ALREADY_BOUND·CONFLICT 다.")
    @Test
    void transactionAlreadyBound() {
        PaymentModel payment = payment();
        PaymentObservation.TransactionObserved shared = tx(ObservationSource.CREATE, "tx-SHARED", ORDER, 7_000L,
            PgTransactionStatus.PENDING);

        ObservationDecision decision = payment.recordTransactionAlreadyBound(create(), shared, T1);

        assertAll(
            () -> assertThat(decision).isEqualTo(ObservationDecision.conflict(
                PaymentConflictCode.TRANSACTION_ALREADY_BOUND, ConflictEvidence.of(shared))),
            () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN),
            () -> assertThat(payment.getTransactionKey()).isNull(),
            () -> assertThat(payment.getVerificationStatus()).isEqualTo(VerificationStatus.CONFLICT)
        );
    }

    @DisplayName("토큰이 이 결제·출처와 맞지 않거나 형식이 틀린 키는 내부 오류다.")
    @Test
    void rejectsMismatchedToken() {
        PaymentModel payment = payment();
        PaymentObservation.TransactionObserved created = tx(ObservationSource.CREATE, KA, ORDER, 7_000L, PgTransactionStatus.PENDING);

        assertAll(
            () -> assertThatThrownBy(() -> payment.recordObservation(
                new PaymentCheckToken(1L, ORDER_ID, ObservationSource.CREATE, false), created, T1))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> payment.recordObservation(
                new PaymentCheckToken(0L, 43L, ObservationSource.CREATE, false), created, T1))
                .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> payment.recordObservation(query(false), created, T1))
                .as("출처 불일치").extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> payment.bindTransaction("bad key"))
                .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN)
        );
    }
}
