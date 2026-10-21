package com.loopers.payment.domain;

import com.loopers.common.domain.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 21, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    private static final ZonedDateTime LATER = NOW.plusSeconds(5);
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final String TX_KEY = "20261021:TR:abc";
    private static final Money AMOUNT = Money.of(7_000);

    private static Payment requested() {
        return Payment.request(42L, 7L, "attempt-1", "LOCAL_PG", AMOUNT, NOW);
    }

    private static Payment sending() {
        Payment payment = requested();
        payment.markSending(NOW);
        return payment;
    }

    private static Payment pending() {
        Payment payment = sending();
        payment.recordAccepted(TX_KEY, NOW);
        return payment;
    }

    private static Payment unknown() {
        Payment payment = sending();
        payment.recordUnknown(NOW);
        return payment;
    }

    private static Payment succeeded() {
        Payment payment = pending();
        payment.applyObservation(observed(ExternalPaymentStatus.SUCCESS), NOW);
        return payment;
    }

    private static Payment failed() {
        Payment payment = pending();
        payment.applyObservation(observed(ExternalPaymentStatus.FAILED), NOW);
        return payment;
    }

    private static PaymentObservation observed(ExternalPaymentStatus status) {
        return new PaymentObservation(TX_KEY, PROVIDER_ORDER_ID, AMOUNT, status, status == ExternalPaymentStatus.FAILED ? "한도 초과" : null);
    }

    @DisplayName("결제 시도를 만들 때, ")
    @Nested
    class Request {

        @DisplayName("P-1 REQUESTED로 저장되고, PG 주문 식별자는 LP-ORD-0000000042다.")
        @Test
        void createsRequested() {
            // act
            Payment payment = requested();

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(payment.getOrderId()).isEqualTo(42L);
            assertThat(payment.getUserId()).isEqualTo(7L);
            assertThat(payment.getAttemptKey()).isEqualTo("attempt-1");
            assertThat(payment.getProvider()).isEqualTo("LOCAL_PG");
            assertThat(payment.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
            assertThat(payment.getAmount()).isEqualTo(Money.of(7_000));
            assertThat(payment.getRequestedAt()).isEqualTo(NOW);
            assertThat(payment.getTransactionKey()).isNull();
            assertThat(payment.getCheckCount()).isZero();
            assertThat(payment.isFinal()).isFalse();
        }

        @DisplayName("P-1 금액이 0이면 BAD_REQUEST로 거절한다.")
        @Test
        void rejectsZeroAmount() {
            assertThatThrownBy(() -> Payment.request(42L, 7L, "attempt-1", "LOCAL_PG", Money.ZERO, NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("P-1 시도 키가 비어 있으면 BAD_REQUEST로 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        void rejectsBlankAttemptKey(String attemptKey) {
            assertThatThrownBy(() -> Payment.request(42L, 7L, attemptKey, "LOCAL_PG", AMOUNT, NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("P-1 시도 키가 null이면 BAD_REQUEST로 거절한다.")
        @Test
        void rejectsNullAttemptKey() {
            assertThatThrownBy(() -> Payment.request(42L, 7L, null, "LOCAL_PG", AMOUNT, NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("발송 권한을 얻을 때, ")
    @Nested
    class MarkSending {

        @DisplayName("P-2 REQUESTED면 SENDING이 되고 발송 시각을 남긴다.")
        @Test
        void requestedBecomesSending() {
            // arrange
            Payment payment = requested();

            // act
            payment.markSending(LATER);

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SENDING);
            assertThat(payment.getSentAt()).isEqualTo(LATER);
        }

        @DisplayName("P-3 이미 SENDING이면 CONFLICT로 거절하고, 발송 시각은 처음 값 그대로다.")
        @Test
        void rejectsSecondSending() {
            // arrange
            Payment payment = sending();

            // act & assert
            assertThatThrownBy(() -> payment.markSending(LATER))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SENDING);
            assertThat(payment.getSentAt()).isEqualTo(NOW);
        }

        @DisplayName("P-3 종결(FAILED)이면 CONFLICT로 거절하고 상태는 그대로다.")
        @Test
        void rejectsSendingAfterFinal() {
            // arrange
            Payment payment = requested();
            payment.closeNotSent(NOW);

            // act & assert
            assertThatThrownBy(() -> payment.markSending(LATER))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }
    }

    @DisplayName("PG 생성 응답을 기록할 때, ")
    @Nested
    class CreateResponse {

        @DisplayName("P-6 SENDING에서 수락(PENDING)이면 거래 키와 함께 PENDING이 되고 NOT_FINAL이다.")
        @Test
        void acceptedBecomesPending() {
            // arrange
            Payment payment = sending();

            // act
            PaymentApplyOutcome outcome = payment.recordAccepted(TX_KEY, LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
            assertThat(payment.getPgStatus()).isEqualTo(ExternalPaymentStatus.PENDING);
            assertThat(payment.isFinal()).isFalse();
        }

        @DisplayName("P-6 UNKNOWN 뒤 늦게 온 수락 응답도 PENDING으로 기록한다 (NOT_FINAL).")
        @Test
        void lateAcceptedAfterUnknownBecomesPending() {
            // arrange
            Payment payment = unknown();

            // act
            PaymentApplyOutcome outcome = payment.recordAccepted(TX_KEY, LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("P-6 이미 같은 키로 PENDING이면 ALREADY_APPLIED, 다른 키면 MISMATCH이고 키는 그대로다.")
        @Test
        void acceptedTwice() {
            // arrange
            Payment payment = pending();

            // act
            PaymentApplyOutcome same = payment.recordAccepted(TX_KEY, LATER);
            PaymentApplyOutcome other = payment.recordAccepted("20261021:TR:zzz", LATER);

            // assert
            assertThat(same).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(other).isEqualTo(PaymentApplyOutcome.MISMATCH);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("P-7 SENDING에서 timeout이면 UNKNOWN이 되고 NOT_FINAL이다.")
        @Test
        void timeoutBecomesUnknown() {
            // arrange
            Payment payment = sending();

            // act
            PaymentApplyOutcome outcome = payment.recordUnknown(LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        }

        @DisplayName("P-7 PENDING에서 늦은 timeout은 UNKNOWN으로 내리지 않는다 (ALREADY_APPLIED, 키 유지).")
        @Test
        void unknownDoesNotDowngradePending() {
            // arrange
            Payment payment = pending();

            // act
            PaymentApplyOutcome outcome = payment.recordUnknown(LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("P-8 SENDING에서 효과 0건 확정 거절이면 FAILED가 되고 APPLIED다.")
        @Test
        void rejectedBecomesFailed() {
            // arrange
            Payment payment = sending();

            // act
            PaymentApplyOutcome outcome = payment.recordRejected("잘못된 카드", LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("잘못된 카드");
            assertThat(payment.getCompletedAt()).isEqualTo(LATER);
            assertThat(payment.isFinal()).isTrue();
        }

        @DisplayName("P-8 이미 FAILED면 다시 거절해도 ALREADY_APPLIED이고 사유·종결 시각은 처음 값이다.")
        @Test
        void rejectedTwice() {
            // arrange
            Payment payment = sending();
            payment.recordRejected("잘못된 카드", NOW);

            // act
            PaymentApplyOutcome outcome = payment.recordRejected("다른 사유", LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.getFailureReason()).isEqualTo("잘못된 카드");
            assertThat(payment.getCompletedAt()).isEqualTo(NOW);
        }
    }

    @DisplayName("보내지 않은 결제를 닫을 때 (ADR-W5-03), ")
    @Nested
    class CloseNotSent {

        @DisplayName("P-4 REQUESTED면 FAILED(NOT_SENT)로 닫고 APPLIED다.")
        @Test
        void closesRequested() {
            // arrange
            Payment payment = requested();

            // act
            PaymentApplyOutcome outcome = payment.closeNotSent(LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("NOT_SENT");
            assertThat(payment.getCompletedAt()).isEqualTo(LATER);
        }

        @DisplayName("P-5 SENDING이어도(회로 OPEN으로 호출 전 차단) FAILED(NOT_SENT)로 닫고 APPLIED다.")
        @Test
        void closesSending() {
            // arrange
            Payment payment = sending();

            // act
            PaymentApplyOutcome outcome = payment.closeNotSent(LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("NOT_SENT");
        }

        @DisplayName("P-5 PENDING(보냈음)이면 CONFLICT이고 상태·키는 그대로다.")
        @Test
        void refusesPending() {
            // arrange
            Payment payment = pending();

            // act
            PaymentApplyOutcome outcome = payment.closeNotSent(LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.CONFLICT);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
            assertThat(payment.getFailureReason()).isNull();
        }

        @DisplayName("P-5 UNKNOWN(보냈을 수 있음)이면 CONFLICT이고 상태는 그대로다.")
        @Test
        void refusesUnknown() {
            // arrange
            Payment payment = unknown();

            // act
            PaymentApplyOutcome outcome = payment.closeNotSent(LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.CONFLICT);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(payment.getCompletedAt()).isNull();
        }

        @DisplayName("P-4 이미 FAILED면 ALREADY_APPLIED이고 종결 시각은 처음 값이다.")
        @Test
        void closeTwice() {
            // arrange
            Payment payment = requested();
            payment.closeNotSent(NOW);

            // act
            PaymentApplyOutcome outcome = payment.closeNotSent(LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.getCompletedAt()).isEqualTo(NOW);
        }
    }

    @DisplayName("PG 조회 결과를 반영할 때, ")
    @Nested
    class ApplyObservation {

        @DisplayName("P-9 PENDING에서 SUCCESS를 관찰하면 SUCCESS가 되고 APPLIED다.")
        @Test
        void pendingToSuccess() {
            // arrange
            Payment payment = pending();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.SUCCESS), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getPgStatus()).isEqualTo(ExternalPaymentStatus.SUCCESS);
            assertThat(payment.getCompletedAt()).isEqualTo(LATER);
            assertThat(payment.getLastCheckedAt()).isEqualTo(LATER);
            assertThat(payment.getCheckCount()).isEqualTo(1);
            assertThat(payment.isFinal()).isTrue();
        }

        @DisplayName("P-9 UNKNOWN(거래 키 모름)에서 SUCCESS를 관찰하면 거래 키를 받아 SUCCESS가 된다.")
        @Test
        void unknownToSuccessTakesKey() {
            // arrange
            Payment payment = unknown();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.SUCCESS), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("P-9 PENDING에서 FAILED를 관찰하면 PG 사유와 함께 FAILED가 되고 APPLIED다.")
        @Test
        void pendingToFailed() {
            // arrange
            Payment payment = pending();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.FAILED), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("한도 초과");
            assertThat(payment.getPgStatus()).isEqualTo(ExternalPaymentStatus.FAILED);
            assertThat(payment.getCompletedAt()).isEqualTo(LATER);
        }

        @DisplayName("P-9 UNKNOWN에서 PENDING을 관찰하면 거래 키와 함께 PENDING이 되고 NOT_FINAL이다.")
        @Test
        void unknownToPending() {
            // arrange
            Payment payment = unknown();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.PENDING), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
            assertThat(payment.getCompletedAt()).isNull();
        }

        @DisplayName("P-12 SUCCESS에 같은 SUCCESS가 다시 오면 ALREADY_APPLIED이고 종결 시각은 처음 값이다 (R-1).")
        @Test
        void duplicateSuccess() {
            // arrange
            Payment payment = succeeded();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.SUCCESS), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getCompletedAt()).isEqualTo(NOW);
        }

        @DisplayName("P-12 FAILED에 같은 FAILED가 다시 오면 ALREADY_APPLIED다 (R-2).")
        @Test
        void duplicateFailed() {
            // arrange
            Payment payment = failed();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.FAILED), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @DisplayName("P-13 종결 뒤 PENDING 관찰·늦은 생성 응답·timeout은 종결을 되돌리지 못한다 (ALREADY_APPLIED).")
        @Test
        void lateNonFinalAfterFinal() {
            // arrange
            Payment payment = succeeded();

            // act & assert
            assertThat(payment.applyObservation(observed(ExternalPaymentStatus.PENDING), LATER)).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.recordAccepted(TX_KEY, LATER)).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.recordUnknown(LATER)).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.recordRejected("늦은 거절", LATER)).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getFailureReason()).isNull();
        }

        @DisplayName("P-11 SUCCESS 뒤 FAILED를 관찰하면 CONFLICT이고 SUCCESS는 그대로, 이상을 기록한다 (R-4).")
        @Test
        void failedAfterSuccessIsConflict() {
            // arrange
            Payment payment = succeeded();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.FAILED), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.CONFLICT);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getFailureReason()).isNull();
            assertThat(payment.getCompletedAt()).isEqualTo(NOW);
            assertThat(payment.getLastCheckResult()).startsWith("CONFLICT");
            assertThat(payment.getLastCheckedAt()).isEqualTo(LATER);
        }

        @DisplayName("P-11 FAILED 뒤 SUCCESS를 관찰하면 CONFLICT이고 FAILED는 그대로다.")
        @Test
        void successAfterFailedIsConflict() {
            // arrange
            Payment payment = failed();

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(observed(ExternalPaymentStatus.SUCCESS), LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.CONFLICT);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getLastCheckResult()).startsWith("CONFLICT");
        }

        @DisplayName("P-10 금액이 다르면 MISMATCH이고 PENDING 그대로, 이상과 조회 횟수를 기록한다.")
        @Test
        void amountMismatch() {
            // arrange
            Payment payment = pending();
            PaymentObservation wrongAmount = new PaymentObservation(TX_KEY, PROVIDER_ORDER_ID, Money.of(6_999), ExternalPaymentStatus.SUCCESS, null);

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(wrongAmount, LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.MISMATCH);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getCompletedAt()).isNull();
            assertThat(payment.getLastCheckResult()).startsWith("MISMATCH");
            assertThat(payment.getCheckCount()).isEqualTo(1);
            assertThat(payment.getLastCheckedAt()).isEqualTo(LATER);
        }

        @DisplayName("P-10 PG 주문 식별자가 다르면 MISMATCH이고 UNKNOWN 그대로다.")
        @Test
        void providerOrderIdMismatch() {
            // arrange
            Payment payment = unknown();
            PaymentObservation otherOrder = new PaymentObservation(TX_KEY, "LP-ORD-0000000043", AMOUNT, ExternalPaymentStatus.SUCCESS, null);

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(otherOrder, LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.MISMATCH);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(payment.getTransactionKey()).isNull();
        }

        @DisplayName("P-10 이미 거래 키가 있는데 다른 거래 키를 관찰하면 MISMATCH이고 키·상태는 그대로다.")
        @Test
        void transactionKeyMismatch() {
            // arrange
            Payment payment = pending();
            PaymentObservation otherTx = new PaymentObservation("20261021:TR:zzz", PROVIDER_ORDER_ID, AMOUNT, ExternalPaymentStatus.SUCCESS, null);

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(otherTx, LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.MISMATCH);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("P-10 종결 뒤라도 식별이 다르면 MISMATCH다 (상태는 그대로).")
        @Test
        void mismatchCheckedBeforeFinalGuard() {
            // arrange
            Payment payment = succeeded();
            PaymentObservation wrongAmount = new PaymentObservation(TX_KEY, PROVIDER_ORDER_ID, Money.of(1), ExternalPaymentStatus.FAILED, null);

            // act
            PaymentApplyOutcome outcome = payment.applyObservation(wrongAmount, LATER);

            // assert
            assertThat(outcome).isEqualTo(PaymentApplyOutcome.MISMATCH);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }
    }

    @DisplayName("조회 결과를 판단 없이 기록할 때 (0건·복수), ")
    @Nested
    class RecordCheck {

        @DisplayName("P-10 상태는 그대로 두고 조회 횟수·시각·결과만 남긴다.")
        @Test
        void recordsWithoutStateChange() {
            // arrange
            Payment payment = unknown();

            // act
            payment.recordCheck("NOT_FOUND", NOW);
            payment.recordCheck("MULTIPLE:2", LATER);

            // assert
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(payment.getCheckCount()).isEqualTo(2);
            assertThat(payment.getLastCheckedAt()).isEqualTo(LATER);
            assertThat(payment.getLastCheckResult()).isEqualTo("MULTIPLE:2");
        }
    }
}
