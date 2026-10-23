package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 결제 시도의 상태 전이 규칙(design.md 9-3·9-7절)을 DB 없이 확인한다.
 * 이 규칙이 "종결은 한 번만, 종결 뒤에는 덮어쓰지 않는다"를 지키기 때문에, 중복 callback·늦은 응답이 재고를 두 번 만지지 못한다.
 */
class PaymentAttemptModelTest {

    private static final Long ORDER_ID = 42L;
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final long AMOUNT = 7000L;
    private static final String KEY = "TEST-TX-0001";

    private static PaymentAttemptModel newAttempt() {
        return new PaymentAttemptModel(ORDER_ID, 1L, "attempt-key-1", "TEST", AMOUNT);
    }

    private static PgTransaction transaction(PgTransactionStatus status) {
        return new PgTransaction(KEY, PROVIDER_ORDER_ID, AMOUNT, status);
    }

    private static PaymentAttemptModel attemptInStatus(PaymentAttemptStatus target) {
        PaymentAttemptModel attempt = newAttempt();
        switch (target) {
            case REQUESTED -> { }
            case PENDING -> attempt.applyCreate(new PgCreateResult.Accepted(KEY));
            case UNKNOWN -> attempt.applyCreate(new PgCreateResult.Unknown(UnknownReason.TIMEOUT));
            case SUCCESS -> attempt.applyLookup(List.of(transaction(PgTransactionStatus.SUCCESS)));
            case FAILED -> attempt.applyLookup(List.of(transaction(PgTransactionStatus.FAILED)));
        }
        assertThat(attempt.getStatus()).isEqualTo(target);
        return attempt;
    }

    @DisplayName("결제 시도를 만들 때, ")
    @Nested
    class Create {

        @DisplayName("유효한 값이 주어지면, REQUESTED 상태에 PG 주문 번호가 정해지고 거래 키는 아직 없다.")
        @Test
        void startsRequested_withoutTransactionKey() {
            // act
            PaymentAttemptModel attempt = newAttempt();

            // assert
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(attempt.getProviderOrderId()).isEqualTo(PROVIDER_ORDER_ID);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(attempt.getAmount()).isEqualTo(AMOUNT);
        }

        @DisplayName("키가 비어 있거나 100자를 넘거나, 카드 종류가 비어 있거나, 금액이 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenInputIsInvalid() {
            // act
            List<CoreException> results = List.of(
                assertThrows(CoreException.class, () -> new PaymentAttemptModel(ORDER_ID, 1L, " ", "TEST", AMOUNT)),
                assertThrows(CoreException.class, () -> new PaymentAttemptModel(ORDER_ID, 1L, null, "TEST", AMOUNT)),
                assertThrows(CoreException.class, () -> new PaymentAttemptModel(ORDER_ID, 1L, "k".repeat(101), "TEST", AMOUNT)),
                assertThrows(CoreException.class, () -> new PaymentAttemptModel(ORDER_ID, 1L, "key", "", AMOUNT)),
                assertThrows(CoreException.class, () -> new PaymentAttemptModel(ORDER_ID, 1L, "key", "TEST", 0L)),
                assertThrows(CoreException.class, () -> new PaymentAttemptModel(ORDER_ID, null, "key", "TEST", AMOUNT))
            );

            // assert
            assertThat(results).extracting(CoreException::getErrorType).containsOnly(ErrorType.BAD_REQUEST);
        }

        @DisplayName("같은 요청인지는 키와 카드 종류가 모두 같을 때만 참이다.")
        @Test
        void isSameRequest_onlyWhenKeyAndCardTypeMatch() {
            // arrange
            PaymentAttemptModel attempt = newAttempt();

            // act & assert
            assertThat(attempt.isSameRequest("attempt-key-1", "TEST")).isTrue();
            assertThat(attempt.isSameRequest("attempt-key-2", "TEST")).isFalse();
            assertThat(attempt.isSameRequest("attempt-key-1", "OTHER")).isFalse();
        }
    }

    @DisplayName("거래 생성 요청의 관찰을 적용할 때, ")
    @Nested
    class ApplyCreate {

        @DisplayName("접수되면, 거래 키를 저장하고 PENDING이 된다. 결제 성공이 아니라서 효과는 없다.")
        @Test
        void becomesPending_whenAccepted() {
            // arrange
            PaymentAttemptModel attempt = newAttempt();

            // act
            PaymentEffect effect = attempt.applyCreate(new PgCreateResult.Accepted(KEY));

            // assert
            assertThat(effect).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo(KEY);
        }

        @DisplayName("정확한 422(효과 0건)가 발송 직후에 오면, FAILED가 되고 거래 키는 만들지 않는다. 같은 거절이 다시 와도 효과는 한 번뿐이다.")
        @Test
        void fails_whenRejectedNoEffect_andOnlyOnce() {
            // arrange
            PaymentAttemptModel attempt = newAttempt();

            // act
            PaymentEffect first = attempt.applyCreate(new PgCreateResult.RejectedNoEffect());
            PaymentEffect second = attempt.applyCreate(new PgCreateResult.RejectedNoEffect());

            // assert
            assertThat(first).isEqualTo(PaymentEffect.FAILED);
            assertThat(second).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(attempt.getTransactionKey()).isNull();
        }

        @DisplayName("이미 거래를 아는 시도(PENDING·UNKNOWN)에 뒤늦게 거절이 와도, 실패로 정하지 않는다.")
        @Test
        void ignoresLateRejection_whenTransactionIsAlreadyKnownOrUnknown() {
            // arrange
            PaymentAttemptModel pending = attemptInStatus(PaymentAttemptStatus.PENDING);
            PaymentAttemptModel unknown = attemptInStatus(PaymentAttemptStatus.UNKNOWN);

            // act
            PaymentEffect pendingEffect = pending.applyCreate(new PgCreateResult.RejectedNoEffect());
            PaymentEffect unknownEffect = unknown.applyCreate(new PgCreateResult.RejectedNoEffect());

            // assert
            assertThat(pendingEffect).isEqualTo(PaymentEffect.NONE);
            assertThat(unknownEffect).isEqualTo(PaymentEffect.NONE);
            assertThat(pending.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(unknown.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        }

        @DisplayName("결과 미확인이 발송 직후에 오면, UNKNOWN과 이유가 기록된다. 실패가 아니므로 효과는 없다.")
        @Test
        void becomesUnknown_whenCreateResultIsUnknown() {
            // arrange
            PaymentAttemptModel attempt = newAttempt();

            // act
            PaymentEffect effect = attempt.applyCreate(new PgCreateResult.Unknown(UnknownReason.TIMEOUT));

            // assert
            assertThat(effect).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt.getUnknownReason()).isEqualTo(UnknownReason.TIMEOUT);
        }

        @DisplayName("이미 PENDING인 시도에 늦은 timeout이 와도, UNKNOWN으로 되돌리지 않는다.")
        @Test
        void keepsPending_whenLateTimeoutArrives() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.PENDING);

            // act
            attempt.applyCreate(new PgCreateResult.Unknown(UnknownReason.TIMEOUT));

            // assert
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getUnknownReason()).isNull();
        }

        @DisplayName("UNKNOWN인 시도가 나중에 접수 응답의 거래 키를 받으면, PENDING이 되고 이유는 지워진다.")
        @Test
        void becomesPending_whenUnknownAttemptLearnsTransactionKey() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.UNKNOWN);

            // act
            attempt.applyCreate(new PgCreateResult.Accepted(KEY));

            // assert
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getUnknownReason()).isNull();
        }

        @DisplayName("저장된 키와 다른 거래 키가 접수되면, 상태는 그대로 두고 충돌을 기록한다.")
        @Test
        void recordsConflict_whenAcceptedKeyDiffersFromStoredKey() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.PENDING);

            // act
            attempt.applyCreate(new PgCreateResult.Accepted("TEST-TX-9999"));

            // assert
            assertThat(attempt.getTransactionKey()).isEqualTo(KEY);
            assertThat(attempt.getConflict()).isEqualTo(PaymentConflict.TRANSACTION_KEY_MISMATCH);
        }

        @DisplayName("종결된 시도에는 접수·거절·미확인 어떤 늦은 응답도 상태를 되돌리지 않는다.")
        @Test
        void leavesTerminalAttemptUntouched_whenLateCreateResultsArrive() {
            // arrange
            PaymentAttemptModel succeeded = attemptInStatus(PaymentAttemptStatus.SUCCESS);
            PaymentAttemptModel failed = attemptInStatus(PaymentAttemptStatus.FAILED);

            // act
            List<PgCreateResult> lateResults = List.of(
                new PgCreateResult.Accepted(KEY),
                new PgCreateResult.RejectedNoEffect(),
                new PgCreateResult.Unknown(UnknownReason.CONNECTION_LOST)
            );
            lateResults.forEach(result -> {
                assertThat(succeeded.applyCreate(result)).isEqualTo(PaymentEffect.NONE);
                assertThat(failed.applyCreate(result)).isEqualTo(PaymentEffect.NONE);
            });

            // assert
            assertThat(succeeded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(failed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        }
    }

    @DisplayName("거래 조회의 관찰을 적용할 때, ")
    @Nested
    class ApplyLookup {

        @DisplayName("0건이면, 어떤 상태에서도 아무것도 바꾸지 않는다. 거래가 없다는 증거가 아니다.")
        @Test
        void changesNothing_whenNoTransactionIsFound() {
            // arrange
            PaymentAttemptModel unknown = attemptInStatus(PaymentAttemptStatus.UNKNOWN);
            PaymentAttemptModel requested = attemptInStatus(PaymentAttemptStatus.REQUESTED);

            // act
            PaymentEffect unknownEffect = unknown.applyLookup(List.of());
            PaymentEffect requestedEffect = requested.applyLookup(List.of());

            // assert
            assertThat(unknownEffect).isEqualTo(PaymentEffect.NONE);
            assertThat(requestedEffect).isEqualTo(PaymentEffect.NONE);
            assertThat(unknown.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(requested.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(requested.getConflict()).isNull();
        }

        @DisplayName("일치하는 1건이 PENDING이면, 거래 키를 저장하고 PENDING이 된다. 종결하지 않는다.")
        @Test
        void becomesPending_whenSingleMatchingTransactionIsPending() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.UNKNOWN);

            // act
            PaymentEffect effect = attempt.applyLookup(List.of(transaction(PgTransactionStatus.PENDING)));

            // assert
            assertThat(effect).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo(KEY);
            assertThat(attempt.getUnknownReason()).isNull();
        }

        @DisplayName("일치하는 1건이 SUCCESS이면 한 번만 SUCCEEDED 효과를 내고, 같은 결과가 다시 와도 효과가 없다.")
        @Test
        void succeedsOnce_whenSingleMatchingTransactionIsSuccess() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.UNKNOWN);

            // act
            PaymentEffect first = attempt.applyLookup(List.of(transaction(PgTransactionStatus.SUCCESS)));
            PaymentEffect second = attempt.applyLookup(List.of(transaction(PgTransactionStatus.SUCCESS)));

            // assert
            assertThat(first).isEqualTo(PaymentEffect.SUCCEEDED);
            assertThat(second).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(attempt.getConflict()).isNull();
        }

        @DisplayName("일치하는 1건이 FAILED이면 한 번만 FAILED 효과를 내고, 같은 결과가 다시 와도 효과가 없다.")
        @Test
        void failsOnce_whenSingleMatchingTransactionIsFailed() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.PENDING);

            // act
            PaymentEffect first = attempt.applyLookup(List.of(transaction(PgTransactionStatus.FAILED)));
            PaymentEffect second = attempt.applyLookup(List.of(transaction(PgTransactionStatus.FAILED)));

            // assert
            assertThat(first).isEqualTo(PaymentEffect.FAILED);
            assertThat(second).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        }

        @DisplayName("2건 이상이면, 첫 거래를 고르지 않고 충돌만 기록한다.")
        @Test
        void recordsConflictWithoutPicking_whenMultipleTransactionsAreFound() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.UNKNOWN);
            PgTransaction other = new PgTransaction("TEST-TX-0002", PROVIDER_ORDER_ID, AMOUNT, PgTransactionStatus.SUCCESS);

            // act
            PaymentEffect effect = attempt.applyLookup(List.of(transaction(PgTransactionStatus.SUCCESS), other));

            // assert
            assertThat(effect).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(attempt.getConflict()).isEqualTo(PaymentConflict.MULTIPLE_TRANSACTIONS);
        }

        @DisplayName("주문 번호·금액·거래 키가 이 시도와 다르면, SUCCESS여도 확정하지 않고 충돌만 기록한다.")
        @Test
        void recordsConflictWithoutSettling_whenTransactionDoesNotBelongToAttempt() {
            // arrange
            PaymentAttemptModel otherOrder = attemptInStatus(PaymentAttemptStatus.UNKNOWN);
            PaymentAttemptModel otherAmount = attemptInStatus(PaymentAttemptStatus.UNKNOWN);
            PaymentAttemptModel otherKey = attemptInStatus(PaymentAttemptStatus.PENDING);

            // act
            PaymentEffect orderEffect = otherOrder.applyLookup(
                List.of(new PgTransaction(KEY, "LP-ORD-0000000099", AMOUNT, PgTransactionStatus.SUCCESS)));
            PaymentEffect amountEffect = otherAmount.applyLookup(
                List.of(new PgTransaction(KEY, PROVIDER_ORDER_ID, 9000L, PgTransactionStatus.SUCCESS)));
            PaymentEffect keyEffect = otherKey.applyLookup(
                List.of(new PgTransaction("TEST-TX-9999", PROVIDER_ORDER_ID, AMOUNT, PgTransactionStatus.SUCCESS)));

            // assert
            assertThat(List.of(orderEffect, amountEffect, keyEffect)).containsOnly(PaymentEffect.NONE);
            assertThat(otherOrder.getConflict()).isEqualTo(PaymentConflict.ORDER_MISMATCH);
            assertThat(otherAmount.getConflict()).isEqualTo(PaymentConflict.AMOUNT_MISMATCH);
            assertThat(otherKey.getConflict()).isEqualTo(PaymentConflict.TRANSACTION_KEY_MISMATCH);
            assertThat(otherOrder.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(otherAmount.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(otherKey.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        }

        @DisplayName("종결된 시도에 반대되는 최종 결과가 오면, 덮어쓰지 않고 충돌만 기록한다.")
        @Test
        void keepsTerminalResult_andRecordsConflict_whenOppositeFinalResultArrives() {
            // arrange
            PaymentAttemptModel succeeded = attemptInStatus(PaymentAttemptStatus.SUCCESS);
            PaymentAttemptModel failed = attemptInStatus(PaymentAttemptStatus.FAILED);

            // act
            PaymentEffect afterFailed = succeeded.applyLookup(List.of(transaction(PgTransactionStatus.FAILED)));
            PaymentEffect afterSuccess = failed.applyLookup(List.of(transaction(PgTransactionStatus.SUCCESS)));

            // assert
            assertThat(afterFailed).isEqualTo(PaymentEffect.NONE);
            assertThat(afterSuccess).isEqualTo(PaymentEffect.NONE);
            assertThat(succeeded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(failed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(succeeded.getConflict()).isEqualTo(PaymentConflict.TERMINAL_RESULT_MISMATCH);
            assertThat(failed.getConflict()).isEqualTo(PaymentConflict.TERMINAL_RESULT_MISMATCH);
        }

        @DisplayName("종결된 시도에 더 오래된 PENDING 관찰이 늦게 와도, 충돌로 기록하지 않고 그대로 둔다.")
        @Test
        void ignoresStalePending_whenAttemptIsAlreadyTerminal() {
            // arrange
            PaymentAttemptModel attempt = attemptInStatus(PaymentAttemptStatus.SUCCESS);

            // act
            PaymentEffect effect = attempt.applyLookup(List.of(transaction(PgTransactionStatus.PENDING)));

            // assert
            assertThat(effect).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(attempt.getConflict()).isNull();
        }

        @DisplayName("callback이 먼저 종결한 뒤 늦게 돌아온 생성 응답은, 종결 상태와 효과를 바꾸지 않는다.")
        @Test
        void callbackFirstThenLateCreateResponse_convergesToSameTerminalState() {
            // arrange
            PaymentAttemptModel attempt = newAttempt();

            // act
            PaymentEffect byLookup = attempt.applyLookup(List.of(transaction(PgTransactionStatus.SUCCESS)));
            PaymentEffect byLateCreate = attempt.applyCreate(new PgCreateResult.Accepted(KEY));

            // assert
            assertThat(byLookup).isEqualTo(PaymentEffect.SUCCEEDED);
            assertThat(byLateCreate).isEqualTo(PaymentEffect.NONE);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(attempt.getTransactionKey()).isEqualTo(KEY);
        }
    }
}
