package com.loopers.domain.payment;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentAttemptTest {

    private static final String TX_KEY = "TEST-TX-0042";

    private PaymentAttempt requested() {
        return PaymentAttempt.request(42L, "attempt-1", new Money(7_000));
    }

    private PaymentAttempt unknown() {
        PaymentAttempt attempt = requested();
        attempt.markUnknown();
        return attempt;
    }

    private PaymentAttempt pending() {
        PaymentAttempt attempt = requested();
        attempt.markPending(TX_KEY);
        return attempt;
    }

    private void assertErrorType(ErrorType expected, Runnable action) {
        CoreException exception = assertThrows(CoreException.class, action::run);
        assertThat(exception.getErrorType()).isEqualTo(expected);
    }

    @DisplayName("결제 시도를 만들 때,")
    @Nested
    class Request {
        @DisplayName("REQUESTED 상태이고, 주문 42 의 providerOrderId 는 LP-ORD-0000000042 이며, 거래 키와 이상 징후는 없다.")
        @Test
        void createsRequested_withProviderOrderId() {
            // act
            PaymentAttempt attempt = requested();

            // assert
            assertThat(attempt.getOrderId()).isEqualTo(42L);
            assertThat(attempt.getAttemptKey()).isEqualTo("attempt-1");
            assertThat(attempt.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
            assertThat(attempt.getAmount()).isEqualTo(new Money(7_000));
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(attempt.getAnomaly()).isNull();
            assertThat(attempt.isTerminal()).isFalse();
        }

        @DisplayName("providerOrderId 는 PG 의 최소 길이(6자)를 만족한다.")
        @Test
        void providerOrderIdIsLongEnough() {
            assertThat(PaymentAttempt.providerOrderIdOf(1L)).isEqualTo("LP-ORD-0000000001").hasSizeGreaterThanOrEqualTo(6);
        }

        @DisplayName("시도 키가 비어 있거나 64자를 넘으면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   "})
        void rejectsWithInvalidInput_whenAttemptKeyIsBlank(String attemptKey) {
            assertErrorType(
                ErrorType.INVALID_INPUT, () -> PaymentAttempt.request(42L, attemptKey, new Money(7_000))
            );
        }

        @DisplayName("시도 키가 64자까지는 허용하고, 65자부터 거절한다.")
        @Test
        void acceptsAttemptKeyUpTo64() {
            assertThat(PaymentAttempt.request(42L, "a".repeat(64), new Money(1)).getAttemptKey()).hasSize(64);
            assertErrorType(
                ErrorType.INVALID_INPUT, () -> PaymentAttempt.request(42L, "a".repeat(65), new Money(1))
            );
        }

        @DisplayName("주문이나 금액이 없으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void rejectsWithInvalidInput_whenOrderOrAmountIsNull() {
            assertErrorType(ErrorType.INVALID_INPUT, () -> PaymentAttempt.request(null, "attempt-1", new Money(1)));
            assertErrorType(ErrorType.INVALID_INPUT, () -> PaymentAttempt.request(42L, "attempt-1", null));
        }
    }

    @DisplayName("상태를 바꿀 때,")
    @Nested
    class Transition {
        @DisplayName("REQUESTED 는 PENDING(거래 키 기록), UNKNOWN, SUCCEEDED, FAILED 로 갈 수 있다.")
        @Test
        void movesFromRequested() {
            // act & assert
            PaymentAttempt toPending = requested();
            toPending.markPending(TX_KEY);
            assertThat(toPending.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(toPending.getTransactionKey()).isEqualTo(TX_KEY);

            PaymentAttempt toUnknown = requested();
            toUnknown.markUnknown();
            assertThat(toUnknown.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(toUnknown.getTransactionKey()).isNull();

            PaymentAttempt toSucceeded = requested();
            toSucceeded.markSucceeded(TX_KEY);
            assertThat(toSucceeded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(toSucceeded.getTransactionKey()).isEqualTo(TX_KEY);

            PaymentAttempt toFailed = requested();
            toFailed.markFailed(null);
            assertThat(toFailed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        }

        @DisplayName("UNKNOWN 은 PENDING 으로 갈 수 있고 거래 키를 기록한다 (응답을 잃은 거래를 조회로 찾는 경로).")
        @Test
        void movesFromUnknownToPending_andRecordsTransactionKey() {
            // arrange
            PaymentAttempt attempt = unknown();

            // act
            attempt.markPending(TX_KEY);

            // assert
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("PENDING·UNKNOWN 은 SUCCEEDED, FAILED 로 갈 수 있다.")
        @Test
        void movesToTerminal_fromPendingAndUnknown() {
            PaymentAttempt pendingSucceeded = pending();
            pendingSucceeded.markSucceeded(TX_KEY);
            assertThat(pendingSucceeded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);

            PaymentAttempt pendingFailed = pending();
            pendingFailed.markFailed(TX_KEY);
            assertThat(pendingFailed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);

            PaymentAttempt unknownSucceeded = unknown();
            unknownSucceeded.markSucceeded(TX_KEY);
            assertThat(unknownSucceeded.getTransactionKey()).isEqualTo(TX_KEY);

            PaymentAttempt unknownFailed = unknown();
            unknownFailed.markFailed(TX_KEY);
            assertThat(unknownFailed.isTerminal()).isTrue();
        }

        @DisplayName("PENDING 은 UNKNOWN 으로 되돌아가지 않고, 다시 REQUESTED·PENDING 으로도 가지 않는다.")
        @Test
        void rejectsBackwardMoves_fromPending() {
            // arrange
            PaymentAttempt attempt = pending();

            // act & assert
            assertErrorType(ErrorType.CONFLICT, attempt::markUnknown);
            assertErrorType(ErrorType.CONFLICT, () -> attempt.markPending(TX_KEY));
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("UNKNOWN 은 다시 UNKNOWN 이 되지 않는다.")
        @Test
        void rejectsUnknownToUnknown() {
            // arrange
            PaymentAttempt attempt = unknown();

            // act & assert
            assertErrorType(ErrorType.CONFLICT, attempt::markUnknown);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        }

        @DisplayName("종결 상태(SUCCEEDED·FAILED)에서는 어떤 전이도 거절하고, 상태와 거래 키를 유지한다.")
        @Test
        void rejectsAnyMove_whenTerminal() {
            // arrange
            PaymentAttempt succeeded = pending();
            succeeded.markSucceeded(TX_KEY);
            PaymentAttempt failed = pending();
            failed.markFailed(TX_KEY);

            // act & assert
            for (PaymentAttempt terminal : new PaymentAttempt[] {succeeded, failed}) {
                PaymentAttemptStatus before = terminal.getStatus();
                assertErrorType(ErrorType.CONFLICT, terminal::markUnknown);
                assertErrorType(ErrorType.CONFLICT, () -> terminal.markPending(TX_KEY));
                assertErrorType(ErrorType.CONFLICT, () -> terminal.markSucceeded(TX_KEY));
                assertErrorType(ErrorType.CONFLICT, () -> terminal.markFailed(TX_KEY));
                assertThat(terminal.getStatus()).isEqualTo(before);
                assertThat(terminal.getTransactionKey()).isEqualTo(TX_KEY);
                assertThat(terminal.isTerminal()).isTrue();
            }
        }

        @DisplayName("이미 아는 거래 키와 다른 키로는 전이를 거절하고, 상태와 거래 키를 유지한다.")
        @Test
        void rejectsDifferentTransactionKey() {
            // arrange
            PaymentAttempt attempt = pending();

            // act & assert
            assertErrorType(ErrorType.CONFLICT, () -> attempt.markSucceeded("OTHER-TX"));
            assertErrorType(ErrorType.CONFLICT, () -> attempt.markFailed("OTHER-TX"));
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("성공·대기는 거래 키가 필수이고, 확정 실패는 거래 키 없이도 가능하다 (생성 전 거절은 거래가 없다).")
        @Test
        void requiresTransactionKey_exceptForFailure() {
            // arrange
            PaymentAttempt attempt = requested();

            // act & assert
            assertErrorType(ErrorType.INVALID_INPUT, () -> attempt.markSucceeded(null));
            assertErrorType(ErrorType.INVALID_INPUT, () -> attempt.markPending(" "));
            assertErrorType(ErrorType.INVALID_INPUT, () -> attempt.markFailed(" "));
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);

            attempt.markFailed(null);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(attempt.getTransactionKey()).isNull();
        }
    }

    @DisplayName("이상 징후를 기록할 때,")
    @Nested
    class RecordAnomaly {
        @DisplayName("상태와 거래 키는 바꾸지 않고 징후만 남긴다. 종결된 시도에도 기록할 수 있다.")
        @Test
        void recordsAnomaly_withoutChangingStatus() {
            // arrange
            PaymentAttempt attempt = pending();
            attempt.markSucceeded(TX_KEY);

            // act
            attempt.recordAnomaly(PaymentAnomaly.CONFLICTING_RESULT);

            // assert
            assertThat(attempt.getAnomaly()).isEqualTo(PaymentAnomaly.CONFLICTING_RESULT);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY);
        }

        @DisplayName("종류가 없으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void rejectsWithInvalidInput_whenAnomalyIsNull() {
            assertErrorType(ErrorType.INVALID_INPUT, () -> requested().recordAnomaly(null));
        }
    }
}
