package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentAttemptTest {

    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final String TX_KEY = "TEST-TX-0042";
    private static final long AMOUNT = 7000L;

    private static PaymentAttempt requested() {
        return new PaymentAttempt(42L, "key-A", PROVIDER_ORDER_ID, AMOUNT);
    }

    private static PaymentObservation observed(PaymentObservation.Status status) {
        return new PaymentObservation(PROVIDER_ORDER_ID, TX_KEY, status, AMOUNT);
    }

    @DisplayName("결제 시도를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("요청됨 상태로 만들어지고, 발송 시작 시각이 기록된다. (INV-005)")
        @Test
        void createsRequestedAttempt_withSendStartedAt() {
            // act
            PaymentAttempt attempt = requested();

            // assert
            assertAll(
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED),
                () -> assertThat(attempt.getOrderId()).isEqualTo(42L),
                () -> assertThat(attempt.getTransactionKey()).isNull(),
                () -> assertThat(attempt.getSendStartedAt()).isNotNull()
            );
        }

        @DisplayName("결제 시도 키가 비어 있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAttemptKeyIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class,
                () -> new PaymentAttempt(42L, " ", PROVIDER_ORDER_ID, AMOUNT));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("금액이 음수면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class,
                () -> new PaymentAttempt(42L, "key-A", PROVIDER_ORDER_ID, -1L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("create 응답을 반영할 때, ")
    @Nested
    class CreateResponse {
        @DisplayName("202 PENDING이면, 처리중으로 바뀌고 transactionKey가 기록된다. 효과는 없다.")
        @Test
        void becomesPending_whenAccepted() {
            // arrange
            PaymentAttempt attempt = requested();

            // act
            ApplyResult result = attempt.accept(TX_KEY);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }

        @DisplayName("응답을 받지 못하면, 실패가 아니라 UNKNOWN이 된다. (INV-004)")
        @Test
        void becomesUnknown_whenResponseIsLost() {
            // arrange
            PaymentAttempt attempt = requested();

            // act
            attempt.markUnknown();

            // assert
            assertAll(
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN),
                () -> assertThat(attempt.getTransactionKey()).isNull()
            );
        }

        @DisplayName("효과 없는 확정 거절(422)이면, FAILED로 전이하고 효과가 있다. transactionKey는 꾸며 넣지 않는다.")
        @Test
        void fails_whenRejectedBeforeCreate() {
            // arrange
            PaymentAttempt attempt = requested();

            // act
            ApplyResult result = attempt.rejectBeforeCreate();

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.FAILED),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(attempt.getTransactionKey()).isNull()
            );
        }

        @DisplayName("callback이 먼저 SUCCESS로 종결한 뒤 늦게 온 생성 응답·timeout 처리는, 상태를 되돌리지 않는다. (INV-003 반례 1)")
        @Test
        void keepsTerminalStatus_whenLateCreateResponseArrives() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // act
            ApplyResult accepted = attempt.accept(TX_KEY);
            attempt.markUnknown();

            // assert
            assertAll(
                () -> assertThat(accepted).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS)
            );
        }
    }

    @DisplayName("PG 조회 결과를 반영할 때, ")
    @Nested
    class Apply {
        @DisplayName("미종결 시도에 SUCCESS가 확인되면, SUCCESS로 전이하고 효과가 있다.")
        @Test
        void succeeds_whenSuccessIsObservedOnPendingAttempt() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.SUCCEEDED),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS)
            );
        }

        @DisplayName("응답을 잃은(UNKNOWN) 시도에 목록 조회로 거래가 확인되면, transactionKey를 알게 되고 결과로 전이한다.")
        @Test
        void learnsTransactionKeyAndFails_whenFailedIsObservedOnUnknownAttempt() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.markUnknown();

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.FAILED));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.FAILED),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }

        @DisplayName("미종결 시도에 PENDING이 확인되면, 처리중으로 남고 효과는 없다.")
        @Test
        void staysPending_whenPendingIsObserved() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.markUnknown();

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.PENDING));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }

        @DisplayName("같은 FAILED가 두 번 들어오면, 두 번째는 효과가 없다 — 재고가 두 번 반환되지 않는다. (INV-002)")
        @Test
        void hasNoEffect_whenSameFailedArrivesTwice() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);

            // act
            ApplyResult first = attempt.apply(observed(PaymentObservation.Status.FAILED));
            ApplyResult second = attempt.apply(observed(PaymentObservation.Status.FAILED));

            // assert
            assertAll(
                () -> assertThat(first).isEqualTo(ApplyResult.FAILED),
                () -> assertThat(second).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED)
            );
        }

        @DisplayName("FAILED로 종결된 시도에 SUCCESS가 확인되면, 상태를 바꾸지 않고 충돌로 알린다. (INV-003 반례 2)")
        @Test
        void reportsConflict_whenDifferentOutcomeArrivesAfterTerminal() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.rejectBeforeCreate();

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED)
            );
        }

        @DisplayName("SUCCESS로 종결된 시도에 PENDING이 확인되면, 되돌리지 않고 충돌로 알린다.")
        @Test
        void reportsConflict_whenPendingArrivesAfterSuccess() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.PENDING));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS)
            );
        }

        @DisplayName("관찰한 금액이 저장된 금액과 다르면, 미종결이어도 전이하지 않고 충돌로 알린다.")
        @Test
        void reportsConflict_whenAmountDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);
            PaymentObservation otherAmount = new PaymentObservation(
                PROVIDER_ORDER_ID, TX_KEY, PaymentObservation.Status.SUCCESS, AMOUNT + 1);

            // act
            ApplyResult result = attempt.apply(otherAmount);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING)
            );
        }

        @DisplayName("관찰한 주문 식별자가 다르면, 충돌로 알린다.")
        @Test
        void reportsConflict_whenProviderOrderIdDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.markUnknown();
            PaymentObservation otherOrder = new PaymentObservation(
                "LP-ORD-0000000043", TX_KEY, PaymentObservation.Status.SUCCESS, AMOUNT);

            // act
            ApplyResult result = attempt.apply(otherOrder);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN)
            );
        }

        @DisplayName("이미 아는 transactionKey와 다른 거래가 관찰되면, 충돌로 알린다.")
        @Test
        void reportsConflict_whenTransactionKeyDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);
            PaymentObservation otherTransaction = new PaymentObservation(
                PROVIDER_ORDER_ID, "TEST-TX-9999", PaymentObservation.Status.SUCCESS, AMOUNT);

            // act
            ApplyResult result = attempt.apply(otherTransaction);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }
    }

    @DisplayName("충돌 사유를 정할 때, (D-011)")
    @Nested
    class ConflictReason {
        @DisplayName("관찰한 거래의 금액이 저장된 금액과 다르면, MISMATCH다.")
        @Test
        void mismatch_whenAmountDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            PaymentObservation observation = new PaymentObservation(PROVIDER_ORDER_ID, TX_KEY, PaymentObservation.Status.SUCCESS, AMOUNT + 1);

            // act
            ApplyResult result = attempt.apply(observation);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED),
                () -> assertThat(attempt.conflictReasonFor(observation)).isEqualTo(PaymentConflictReason.MISMATCH)
            );
        }

        @DisplayName("이미 아는 거래 키와 다른 거래면, MISMATCH다.")
        @Test
        void mismatch_whenTransactionKeyDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);
            PaymentObservation other = new PaymentObservation(PROVIDER_ORDER_ID, "OTHER-TX", PaymentObservation.Status.SUCCESS, AMOUNT);

            // act & assert
            assertThat(attempt.conflictReasonFor(other)).isEqualTo(PaymentConflictReason.MISMATCH);
        }

        @DisplayName("우리 거래인데 종결된 결과와 다르면, DIFFERENT_OUTCOME_AFTER_TERMINAL이다.")
        @Test
        void differentOutcome_whenSameTransactionAfterTerminal() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.apply(observed(PaymentObservation.Status.FAILED));

            // act & assert
            assertThat(attempt.conflictReasonFor(observed(PaymentObservation.Status.SUCCESS)))
                .isEqualTo(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL);
        }
    }

    @DisplayName("이미 저장된 시도에 같은 요청인지 물을 때, (D-015)")
    @Nested
    class SameRequest {
        @DisplayName("같은 주문·같은 키면 통과한다.")
        @Test
        void passes_forSameOrderAndKey() {
            requested().assertSameRequest(42L, "key-A");
        }

        @DisplayName("같은 주문·다른 키면 CONFLICT, 같은 키·다른 주문이면 BAD_REQUEST다.")
        @Test
        void rejects_otherwise() {
            PaymentAttempt attempt = requested();
            CoreException otherKey = assertThrows(CoreException.class, () -> attempt.assertSameRequest(42L, "key-B"));
            CoreException otherOrder = assertThrows(CoreException.class, () -> attempt.assertSameRequest(43L, "key-A"));
            assertAll(
                () -> assertThat(otherKey.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(otherOrder.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST)
            );
        }
    }

    @DisplayName("회복 작업의 확인 일정은, (D-007, D-008, D-043, D-044)")
    @Nested
    class CheckSchedule {
        private final ZonedDateTime now = ZonedDateTime.parse("2026-10-22T10:00:00+09:00");

        @DisplayName("처음에는 발송 시작 1분 뒤부터 확인 대상이 된다 — 정상 callback을 기다려 준다.")
        @Test
        void firstCheckIsOneMinuteAfterSending() {
            PaymentAttempt attempt = requested();
            assertThat(attempt.getNextCheckAt()).isEqualTo(attempt.getSendStartedAt().plusMinutes(1));
        }

        @DisplayName("0건이 이어지면 1~3번째는 3분, 4번째는 30분, 5번째부터 1시간 뒤에 다시 보고, 3번째에 관리자 확인 요청 시각을 한 번만 적는다.")
        @Test
        void backsOff_andRequestsReviewOnThirdEmptyLookup() {
            PaymentAttempt attempt = requested();

            attempt.recordEmptyLookup(now);
            attempt.recordEmptyLookup(now);
            assertAll(
                () -> assertThat(attempt.getEmptyLookupCount()).isEqualTo(2),
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(now.plusMinutes(3)),
                () -> assertThat(attempt.getReviewRequestedAt()).isNull()
            );

            attempt.recordEmptyLookup(now);
            assertAll(
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(now.plusMinutes(3)),
                () -> assertThat(attempt.getReviewRequestedAt()).isEqualTo(now)
            );

            ZonedDateTime later = now.plusMinutes(3);
            attempt.recordEmptyLookup(later);
            assertAll(
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(later.plusMinutes(30)),
                () -> assertThat(attempt.getReviewRequestedAt()).isEqualTo(now)
            );

            attempt.recordEmptyLookup(later);
            attempt.recordEmptyLookup(later);
            assertAll(
                () -> assertThat(attempt.getEmptyLookupCount()).isEqualTo(6),
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(later.plusHours(1)),
                () -> assertThat(attempt.getReviewRequestedAt()).isEqualTo(now)
            );
        }

        @DisplayName("거래를 보면 연속 0건이 끊기고 3분 뒤 다시 본다.")
        @Test
        void resetsCount_whenTransactionIsFound() {
            PaymentAttempt attempt = requested();
            attempt.recordEmptyLookup(now);
            attempt.recordEmptyLookup(now);

            attempt.recordFound(now);

            assertAll(
                () -> assertThat(attempt.getEmptyLookupCount()).isZero(),
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(now.plusMinutes(3))
            );
        }

        @DisplayName("물어보지 못했으면 0건으로 세지 않고 3분 뒤 다시 본다. (INV-004)")
        @Test
        void keepsCount_whenLookupIsUnavailable() {
            PaymentAttempt attempt = requested();
            attempt.recordEmptyLookup(now);

            attempt.recordUnavailable(now.plusMinutes(3));

            assertAll(
                () -> assertThat(attempt.getEmptyLookupCount()).isEqualTo(1),
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(now.plusMinutes(6))
            );
        }
    }

    @DisplayName("관리자 실패 처리는, (D-009, D-013, D-045)")
    @Nested
    class AdminFail {
        private final ZonedDateTime now = ZonedDateTime.parse("2026-10-23T10:00:00+09:00");

        @DisplayName("거래 키를 모르는 미종결 시도(요청됨·UNKNOWN)를 FAILED로 종결하고, 처리자·시각을 남기며 3일 감시를 6시간 뒤부터 시작한다.")
        @Test
        void failsUnknown_andStartsWatch() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.markUnknown();

            // act
            ApplyResult result = attempt.failByAdmin("admin", now);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.FAILED),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(attempt.getResolvedBy()).isEqualTo("admin"),
                () -> assertThat(attempt.getResolvedAt()).isEqualTo(now),
                () -> assertThat(attempt.getWatchUntil()).isEqualTo(now.plusDays(3)),
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(now.plusHours(6)),
                () -> assertThat(attempt.isWatched(now.plusDays(3))).isTrue(),
                () -> assertThat(attempt.isWatched(now.plusDays(3).plusSeconds(1))).isFalse()
            );
        }

        @DisplayName("요청됨 상태도 실패 처리할 수 있다 — 발송 도중 서버가 죽은 흔적이다(D-018).")
        @Test
        void failsRequested() {
            PaymentAttempt attempt = requested();
            assertThat(attempt.failByAdmin("admin", now)).isEqualTo(ApplyResult.FAILED);
        }

        @DisplayName("처리중(PG가 거래를 만들었다고 답함)이나 종결된 시도는 CONFLICT다.")
        @Test
        void rejectsPendingAndTerminal() {
            PaymentAttempt pending = requested();
            pending.accept(TX_KEY);
            PaymentAttempt failed = requested();
            failed.rejectBeforeCreate();

            CoreException onPending = assertThrows(CoreException.class, () -> pending.failByAdmin("admin", now));
            CoreException onFailed = assertThrows(CoreException.class, () -> failed.failByAdmin("admin", now));

            assertAll(
                () -> assertThat(onPending.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(onFailed.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(pending.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING)
            );
        }

        @DisplayName("처리자가 비어 있으면 BAD_REQUEST다.")
        @Test
        void rejectsBlankResolver() {
            CoreException exception = assertThrows(CoreException.class, () -> requested().failByAdmin(" ", now));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("감시 조회를 마치면 결과와 상관없이 6시간 뒤 다시 본다. 관리자가 처리하지 않은 종결 시도는 감시 대상이 아니다.")
        @Test
        void schedulesNextWatchCheck() {
            PaymentAttempt attempt = requested();
            attempt.failByAdmin("admin", now);
            PaymentAttempt rejected = requested();
            rejected.rejectBeforeCreate();

            attempt.recordWatchCheck(now.plusHours(6));

            assertAll(
                () -> assertThat(attempt.getNextCheckAt()).isEqualTo(now.plusHours(12)),
                () -> assertThat(rejected.isWatched(now)).isFalse()
            );
        }
    }
}
