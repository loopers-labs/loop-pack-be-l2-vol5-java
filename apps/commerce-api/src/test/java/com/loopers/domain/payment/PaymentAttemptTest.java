package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAttemptTest {

    private static final String KEY = "8f14e45f-ceea-467a-9575-2c9b3e5a1d20";
    private static final String SUFFIX = "7f3a9c1e";
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042-7f3a9c1e";
    private static final ZonedDateTime DETECTED_AT = ZonedDateTime.parse("2026-10-21T10:00:00+09:00");

    @Nested
    class Create {

        @DisplayName("시도는 거래 키와 충돌 사유 없이 REQUESTED로 시작하고 주문 ID와 금액을 보관한다.")
        @Test
        void startsAsRequested() {
            PaymentAttempt attempt = PaymentAttempt.create(KEY, 42L, 7_000L, SUFFIX);

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(attempt.getOrderId()).isEqualTo(42L);
            assertThat(attempt.getAmount()).isEqualTo(7_000L);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(attempt.getConflictReason()).isNull();
            assertThat(attempt.isTerminal()).isFalse();
        }

        @DisplayName("시도 키는 대소문자를 구분하지 않고 소문자로 정규화해 보관한다.")
        @Test
        void normalizesAttemptKeyToLowerCase() {
            PaymentAttempt attempt = PaymentAttempt.create("8F14E45F-CEEA-467A-9575-2C9B3E5A1D20", 42L, 7_000L, SUFFIX);

            assertThat(attempt.getPaymentAttemptKey()).isEqualTo(KEY);
        }

        @DisplayName("PG 주문 식별자는 주문 ID 10자리와 주입받은 무작위 값으로 만든다.")
        @Test
        void buildsProviderOrderIdFromOrderIdAndSuffix() {
            PaymentAttempt attempt = PaymentAttempt.create(KEY, 42L, 7_000L, SUFFIX);

            assertThat(attempt.getProviderOrderId()).isEqualTo("LP-ORD-0000000042-7f3a9c1e");
        }

        @DisplayName("시도 키가 비어 있으면 BAD_REQUEST로 거절한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        void rejectsBlankKey(String key) {
            assertBadRequest(() -> PaymentAttempt.create(key, 42L, 7_000L, SUFFIX));
        }

        @DisplayName("시도 키가 표준 UUID 텍스트가 아니면 BAD_REQUEST로 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = {
            "key-1",
            "8f14e45fceea467a95752c9b3e5a1d20",
            "8f14e45f-ceea-467a-9575-2c9b3e5a1d2",
            "8f14e45f-ceea-467a-9575-2c9b3e5a1d200",
            "zf14e45f-ceea-467a-9575-2c9b3e5a1d20",
            " 8f14e45f-ceea-467a-9575-2c9b3e5a1d20"
        })
        void rejectsNonUuidKey(String key) {
            assertBadRequest(() -> PaymentAttempt.create(key, 42L, 7_000L, SUFFIX));
        }

        @DisplayName("주문 ID가 없으면 BAD_REQUEST로 거절한다.")
        @Test
        void rejectsMissingOrderId() {
            assertBadRequest(() -> PaymentAttempt.create(KEY, null, 7_000L, SUFFIX));
        }

        @DisplayName("금액이 양수가 아니면 BAD_REQUEST로 거절한다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void rejectsNonPositiveAmount(long amount) {
            assertBadRequest(() -> PaymentAttempt.create(KEY, 42L, amount, SUFFIX));
        }

        @DisplayName("생성기가 소문자 16진수 8자리가 아닌 무작위 값을 넘기면 업무 오류가 아닌 프로그래밍 오류로 거절한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"7f3a9c1", "7f3a9c1e0", "7F3A9C1E", "7f3a9c1g"})
        void rejectsInvalidSuffixFromGenerator(String suffix) {
            assertThatThrownBy(() -> PaymentAttempt.create(KEY, 42L, 7_000L, suffix))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Accept {

        @DisplayName("REQUESTED 시도는 거래 키와 함께 PENDING이 된다.")
        @Test
        void recordsTransactionKeyAsPending() {
            PaymentAttempt attempt = requested();

            assertThat(attempt.accept("TX-1")).isTrue();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("UNKNOWN 시도도 조회로 거래를 찾으면 PENDING이 된다.")
        @Test
        void movesUnknownToPending() {
            PaymentAttempt attempt = requested();
            attempt.markUnknown();

            assertThat(attempt.accept("TX-1")).isTrue();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("같은 거래 키의 PENDING은 중복이라 바꾸지 않는다.")
        @Test
        void treatsSameKeyPendingAsDuplicate() {
            PaymentAttempt attempt = pending("TX-1");

            assertThat(attempt.accept("TX-1")).isFalse();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("이미 기록한 거래 키와 다른 키는 반영하지 않는다.")
        @Test
        void ignoresDifferentTransactionKey() {
            PaymentAttempt attempt = pending("TX-1");

            assertThat(attempt.accept("TX-2")).isFalse();

            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("종결된 시도는 PENDING으로 되돌리지 않는다.")
        @Test
        void doesNotReopenTerminalAttempt() {
            PaymentAttempt attempt = requested();
            attempt.succeed("TX-1");

            assertThat(attempt.accept("TX-1")).isFalse();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("거래 키가 비어 있으면 기록하지 않고 예외를 던진다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        void rejectsBlankTransactionKey(String transactionKey) {
            PaymentAttempt attempt = requested();

            assertThatThrownBy(() -> attempt.accept(transactionKey)).isInstanceOf(IllegalArgumentException.class);

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        }
    }

    @Nested
    class MarkUnknown {

        @DisplayName("REQUESTED 시도만 UNKNOWN이 된다.")
        @Test
        void marksRequestedAsUnknown() {
            PaymentAttempt attempt = requested();

            assertThat(attempt.markUnknown()).isTrue();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        }

        @DisplayName("PENDING이나 종결된 시도는 UNKNOWN으로 바꾸지 않는다.")
        @Test
        void keepsPendingAndTerminalAttempts() {
            PaymentAttempt pending = pending("TX-1");
            PaymentAttempt rejected = requested();
            rejected.rejectBeforeCreate();

            assertThat(pending.markUnknown()).isFalse();
            assertThat(rejected.markUnknown()).isFalse();

            assertThat(pending.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(rejected.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        }
    }

    @Nested
    class Terminate {

        @DisplayName("미종결 시도는 조회로 확인한 거래 키와 함께 성공이나 실패로 한 번 종결된다.")
        @Test
        void terminatesOnceWithTransactionKey() {
            PaymentAttempt succeeded = requested();
            PaymentAttempt failed = requested();
            failed.markUnknown();

            assertThat(succeeded.succeed("TX-1")).isTrue();
            assertThat(failed.fail("TX-2")).isTrue();

            assertThat(succeeded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(succeeded.getTransactionKey()).isEqualTo("TX-1");
            assertThat(failed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(failed.getTransactionKey()).isEqualTo("TX-2");
            assertThat(succeeded.isTerminal()).isTrue();
            assertThat(failed.isTerminal()).isTrue();
        }

        @DisplayName("PENDING 시도는 기록한 거래 키와 같은 키로만 종결된다.")
        @Test
        void terminatesPendingOnlyWithSameKey() {
            PaymentAttempt same = pending("TX-1");
            PaymentAttempt different = pending("TX-1");

            assertThat(same.succeed("TX-1")).isTrue();
            assertThat(different.fail("TX-2")).isFalse();

            assertThat(same.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(different.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(different.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("종결된 시도는 다시 종결하거나 다른 결과로 바꾸지 않는다.")
        @Test
        void keepsFirstTerminalResult() {
            PaymentAttempt attempt = requested();
            attempt.succeed("TX-1");

            assertThat(attempt.succeed("TX-1")).isFalse();
            assertThat(attempt.fail("TX-1")).isFalse();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("실패 종결은 한 번만 성공하고 중복 실패·상반 결과·늦은 관찰에도 실패를 유지한다.")
        @Test
        void keepsFailureWhenDuplicateAndConflictingObservationsArrive() {
            PaymentAttempt attempt = pending("TX-1");

            assertThat(attempt.fail("TX-1")).isTrue();

            assertThat(attempt.fail("TX-1")).isFalse();
            assertThat(attempt.fail("TX-2")).isFalse();
            assertThat(attempt.succeed("TX-1")).isFalse();
            assertThat(attempt.accept("TX-1")).isFalse();
            assertThat(attempt.markUnknown()).isFalse();
            assertThat(attempt.rejectBeforeCreate()).isFalse();
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("거래 키 없이 PG 거래 결과로 종결하지 않는다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        void rejectsBlankTransactionKey(String transactionKey) {
            PaymentAttempt attempt = requested();

            assertThatThrownBy(() -> attempt.succeed(transactionKey)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> attempt.fail(transactionKey)).isInstanceOf(IllegalArgumentException.class);

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        }
    }

    @Nested
    class RejectBeforeCreate {

        @DisplayName("거래 키가 없는 REQUESTED·UNKNOWN 시도는 생성 전 거절로 FAILED가 되고 키는 비어 있다.")
        @Test
        void failsWithoutTransactionKey() {
            PaymentAttempt requested = requested();
            PaymentAttempt unknown = requested();
            unknown.markUnknown();

            assertThat(requested.rejectBeforeCreate()).isTrue();
            assertThat(unknown.rejectBeforeCreate()).isTrue();

            assertThat(requested.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(requested.getTransactionKey()).isNull();
            assertThat(unknown.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(unknown.getTransactionKey()).isNull();
        }

        @DisplayName("이미 거래를 확인한 시도에는 생성 전 거절을 적용하지 않는다.")
        @Test
        void doesNotRejectAttemptWithTransaction() {
            PaymentAttempt attempt = pending("TX-1");

            assertThat(attempt.rejectBeforeCreate()).isFalse();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("같은 거절의 중복이나 종결된 시도에는 다시 적용하지 않는다.")
        @Test
        void keepsTerminalAttempts() {
            PaymentAttempt rejected = requested();
            rejected.rejectBeforeCreate();
            PaymentAttempt succeeded = requested();
            succeeded.succeed("TX-1");

            assertThat(rejected.rejectBeforeCreate()).isFalse();
            assertThat(succeeded.rejectBeforeCreate()).isFalse();

            assertThat(rejected.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(succeeded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(succeeded.getTransactionKey()).isEqualTo("TX-1");
        }
    }

    @Nested
    class RecordConflict {

        @DisplayName("미종결 시도에 처음 충돌 사유를 기록하고 상태와 거래 키는 바꾸지 않는다.")
        @Test
        void recordsFirstConflictWithoutChangingState() {
            PaymentAttempt attempt = pending("TX-1");

            assertThat(attempt.recordConflict(PaymentConflictReason.TRANSACTION_MISMATCH, DETECTED_AT)).isTrue();

            assertThat(attempt.getConflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(attempt.getConflictDetectedAt()).isEqualTo(DETECTED_AT);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("이미 기록한 충돌 사유와 시각은 다른 사유로 덮어쓰지 않는다.")
        @Test
        void keepsFirstConflict() {
            PaymentAttempt attempt = requested();
            attempt.recordConflict(PaymentConflictReason.TRANSACTION_MISMATCH, DETECTED_AT);

            assertThat(attempt.recordConflict(PaymentConflictReason.RESULT_CONTRADICTION, DETECTED_AT.plusMinutes(1)))
                .isFalse();

            assertThat(attempt.getConflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(attempt.getConflictDetectedAt()).isEqualTo(DETECTED_AT);
        }

        @DisplayName("종결된 시도에도 모순 관찰을 기록하지만 최종 결과는 유지한다.")
        @Test
        void recordsContradictionOnTerminalAttemptWithoutChangingResult() {
            PaymentAttempt attempt = requested();
            attempt.succeed("TX-1");

            assertThat(attempt.recordConflict(PaymentConflictReason.RESULT_CONTRADICTION, DETECTED_AT)).isTrue();

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(attempt.getTransactionKey()).isEqualTo("TX-1");
            assertThat(attempt.getConflictReason()).isEqualTo(PaymentConflictReason.RESULT_CONTRADICTION);
        }

        @DisplayName("충돌 사유나 시각이 없으면 기록하지 않고 예외를 던진다.")
        @Test
        void rejectsMissingReasonOrTime() {
            PaymentAttempt attempt = requested();

            assertThatThrownBy(() -> attempt.recordConflict(null, DETECTED_AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> attempt.recordConflict(PaymentConflictReason.TRANSACTION_MISMATCH, null))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(attempt.getConflictReason()).isNull();
        }
    }

    @Nested
    class VerifyTransaction {

        @DisplayName("조회한 거래의 PG 주문 식별자·금액이 시도와 같고 기록한 키와 충돌하지 않으면 통과한다.")
        @Test
        void acceptsTransactionOfThisAttempt() {
            assertThat(requested().verifyTransaction(PROVIDER_ORDER_ID, 7_000L, "TX-1")).isEmpty();
            assertThat(pending("TX-1").verifyTransaction(PROVIDER_ORDER_ID, 7_000L, "TX-1")).isEmpty();
        }

        @DisplayName("PG 주문 식별자·금액이 다르거나 기록한 거래 키와 다르면 TRANSACTION_MISMATCH다.")
        @Test
        void detectsMismatchedTransaction() {
            assertThat(requested().verifyTransaction("LP-ORD-0000000042-00000000", 7_000L, "TX-1"))
                .contains(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(requested().verifyTransaction(PROVIDER_ORDER_ID, 7_001L, "TX-1"))
                .contains(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(pending("TX-1").verifyTransaction(PROVIDER_ORDER_ID, 7_000L, "TX-2"))
                .contains(PaymentConflictReason.TRANSACTION_MISMATCH);
        }
    }

    @Nested
    class Observe {

        @DisplayName("조회한 거래 상태에 따라 PENDING 기록이나 종결로 전이한다.")
        @Test
        void transitionsByObservedStatus() {
            PaymentAttempt pending = requested();
            PaymentAttempt succeeded = requested();
            PaymentAttempt failed = requested();

            assertThat(pending.observe("TX-1", PaymentAttemptStatus.PENDING)).isTrue();
            assertThat(succeeded.observe("TX-1", PaymentAttemptStatus.SUCCEEDED)).isTrue();
            assertThat(failed.observe("TX-1", PaymentAttemptStatus.FAILED)).isTrue();

            assertThat(pending.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(succeeded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(failed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(failed.getTransactionKey()).isEqualTo("TX-1");
        }

        @DisplayName("PG 거래 상태가 아닌 값은 관찰로 받지 않는다.")
        @ParameterizedTest
        @EnumSource(value = PaymentAttemptStatus.class, names = {"REQUESTED", "UNKNOWN"})
        void rejectsNonTransactionStatus(PaymentAttemptStatus status) {
            assertThatThrownBy(() -> requested().observe("TX-1", status)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ExplainUnchanged {

        @DisplayName("같은 키의 PENDING 중복, 종결 뒤 늦은 PENDING, 같은 종결 결과의 중복은 충돌이 아니다.")
        @Test
        void acceptsDuplicateAndLateTransactionObservations() {
            assertThat(pending("TX-1").explainUnchangedTransaction("TX-1", PaymentAttemptStatus.PENDING)).isEmpty();
            assertThat(succeeded("TX-1").explainUnchangedTransaction("TX-1", PaymentAttemptStatus.PENDING)).isEmpty();
            assertThat(succeeded("TX-1").explainUnchangedTransaction("TX-1", PaymentAttemptStatus.SUCCEEDED)).isEmpty();
        }

        @DisplayName("종결과 반대인 결과, 거절로 종결한 시도의 거래는 RESULT_CONTRADICTION이다.")
        @Test
        void detectsResultContradiction() {
            assertThat(succeeded("TX-1").explainUnchangedTransaction("TX-1", PaymentAttemptStatus.FAILED))
                .contains(PaymentConflictReason.RESULT_CONTRADICTION);
            assertThat(rejected().explainUnchangedTransaction("TX-1", PaymentAttemptStatus.PENDING))
                .contains(PaymentConflictReason.RESULT_CONTRADICTION);
        }

        @DisplayName("기록한 거래 키와 다른 거래는 TRANSACTION_MISMATCH다.")
        @Test
        void detectsDifferentTransactionKey() {
            assertThat(pending("TX-1").explainUnchangedTransaction("TX-2", PaymentAttemptStatus.PENDING))
                .contains(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(succeeded("TX-1").explainUnchangedTransaction("TX-2", PaymentAttemptStatus.SUCCEEDED))
                .contains(PaymentConflictReason.TRANSACTION_MISMATCH);
        }

        @DisplayName("전이할 수 있었던 상태에서 갱신되지 않은 거래 관찰은 규칙으로 설명되지 않는 정합성 오류다.")
        @Test
        void treatsUnexplainedTransactionObservationAsIntegrityError() {
            assertInternalError(() -> requested().explainUnchangedTransaction("TX-1", PaymentAttemptStatus.PENDING));
            assertInternalError(() -> pending("TX-1").explainUnchangedTransaction("TX-1", PaymentAttemptStatus.SUCCEEDED));
        }

        @DisplayName("같은 거절은 중복이고, 거래를 확인한 시도의 거절은 RESULT_CONTRADICTION이다.")
        @Test
        void explainsRejection() {
            assertThat(rejected().explainUnchangedRejection()).isEmpty();
            assertThat(pending("TX-1").explainUnchangedRejection()).contains(PaymentConflictReason.RESULT_CONTRADICTION);
            assertThat(succeeded("TX-1").explainUnchangedRejection()).contains(PaymentConflictReason.RESULT_CONTRADICTION);
            assertInternalError(() -> requested().explainUnchangedRejection());
        }

        @DisplayName("UNKNOWN 중복과 PENDING·종결 뒤의 UNKNOWN은 상태를 유지하고, REQUESTED에서 갱신되지 않으면 정합성 오류다.")
        @Test
        void explainsUnknownCreateResponse() {
            PaymentAttempt unknown = requested();
            unknown.markUnknown();

            assertThat(unknown.explainUnchangedUnknown()).isEmpty();
            assertThat(pending("TX-1").explainUnchangedUnknown()).isEmpty();
            assertThat(rejected().explainUnchangedUnknown()).isEmpty();
            assertInternalError(() -> requested().explainUnchangedUnknown());
        }
    }

    @DisplayName("같은 주문과 같은 금액일 때만 같은 결제 요청으로 본다.")
    @Test
    void matchesSameOrderAndAmount() {
        PaymentAttempt attempt = requested();

        assertThat(attempt.matches(42L, 7_000L)).isTrue();
        assertThat(attempt.matches(43L, 7_000L)).isFalse();
        assertThat(attempt.matches(42L, 7_001L)).isFalse();
    }

    private PaymentAttempt requested() {
        return PaymentAttempt.create(KEY, 42L, 7_000L, SUFFIX);
    }

    private PaymentAttempt pending(String transactionKey) {
        PaymentAttempt attempt = requested();
        attempt.accept(transactionKey);
        return attempt;
    }

    private PaymentAttempt succeeded(String transactionKey) {
        PaymentAttempt attempt = requested();
        attempt.succeed(transactionKey);
        return attempt;
    }

    private PaymentAttempt rejected() {
        PaymentAttempt attempt = requested();
        attempt.rejectBeforeCreate();
        return attempt;
    }

    private void assertInternalError(Runnable judgement) {
        assertThatThrownBy(judgement::run)
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR)
            );
    }

    private void assertBadRequest(Runnable creation) {
        assertThatThrownBy(creation::run)
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST)
            );
    }
}
