package com.loopers.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class PaymentConflictTest {

    private static final PaymentObservation LATE_SUCCESS =
        new PaymentObservation("LP-ORD-0000000042", "TEST-TX-0042", PaymentObservation.Status.SUCCESS, 7000L);

    private static PaymentConflict failedButPgSucceeded() {
        return new PaymentConflict(7L, PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL,
            PaymentAttemptStatus.FAILED, LATE_SUCCESS);
    }

    @DisplayName("충돌을 기록할 때, ")
    @Nested
    class Create {
        @DisplayName("기록된 결제 상태와 PG 관찰 결과를 함께 남기고, 처음·마지막 발견 시각이 같다.")
        @Test
        void recordsBothSides() {
            // act
            PaymentConflict conflict = failedButPgSucceeded();

            // assert
            assertAll(
                () -> assertThat(conflict.getRecordedStatus()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(conflict.getObservedStatus()).isEqualTo(PaymentObservation.Status.SUCCESS),
                () -> assertThat(conflict.getObservedTransactionKey()).isEqualTo("TEST-TX-0042"),
                () -> assertThat(conflict.getObservedAmount()).isEqualTo(7000L),
                () -> assertThat(conflict.getLastDetectedAt()).isEqualTo(conflict.getFirstDetectedAt()),
                () -> assertThat(conflict.isResolved()).isFalse()
            );
        }

        @DisplayName("거래가 2건 이상이면, 관찰 결과 없이 사유만 남긴다.")
        @Test
        void recordsWithoutObservation_whenMultipleTransactions() {
            // act
            PaymentConflict conflict = new PaymentConflict(7L, PaymentConflictReason.MULTIPLE_TRANSACTIONS,
                PaymentAttemptStatus.UNKNOWN, null);

            // assert
            assertAll(
                () -> assertThat(conflict.getObservedStatus()).isNull(),
                () -> assertThat(conflict.getObservedTransactionKey()).isNull(),
                () -> assertThat(conflict.getObservedAmount()).isNull()
            );
        }
    }

    @DisplayName("같은 내용인지 판단할 때, (D-012)")
    @Nested
    class SameContent {
        @DisplayName("사유·기록된 결제 상태·PG 관찰 결과가 모두 같으면, 같은 내용이다.")
        @Test
        void isSame_whenEverythingMatches() {
            // arrange
            PaymentConflict conflict = failedButPgSucceeded();

            // act & assert
            assertThat(conflict.hasSameContentAs(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL,
                PaymentAttemptStatus.FAILED, LATE_SUCCESS)).isTrue();
        }

        @DisplayName("관찰한 금액이 다르면, 새 정보라서 다른 내용이다.")
        @Test
        void isDifferent_whenObservedAmountDiffers() {
            // arrange
            PaymentConflict conflict = failedButPgSucceeded();
            PaymentObservation otherAmount = new PaymentObservation(
                "LP-ORD-0000000042", "TEST-TX-0042", PaymentObservation.Status.SUCCESS, 9000L);

            // act & assert
            assertThat(conflict.hasSameContentAs(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL,
                PaymentAttemptStatus.FAILED, otherAmount)).isFalse();
        }

        @DisplayName("사유나 기록된 결제 상태가 다르면, 다른 내용이다.")
        @Test
        void isDifferent_whenReasonOrRecordedStatusDiffers() {
            // arrange
            PaymentConflict conflict = failedButPgSucceeded();

            // act & assert
            assertAll(
                () -> assertThat(conflict.hasSameContentAs(PaymentConflictReason.MISMATCH,
                    PaymentAttemptStatus.FAILED, LATE_SUCCESS)).isFalse(),
                () -> assertThat(conflict.hasSameContentAs(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL,
                    PaymentAttemptStatus.SUCCESS, LATE_SUCCESS)).isFalse()
            );
        }

        @DisplayName("거래 2건 이상 충돌끼리는, 관찰 결과가 둘 다 없어도 같은 내용이다.")
        @Test
        void isSame_whenBothHaveNoObservation() {
            // arrange
            PaymentConflict conflict = new PaymentConflict(7L, PaymentConflictReason.MULTIPLE_TRANSACTIONS,
                PaymentAttemptStatus.UNKNOWN, null);

            // act & assert
            assertThat(conflict.hasSameContentAs(PaymentConflictReason.MULTIPLE_TRANSACTIONS,
                PaymentAttemptStatus.UNKNOWN, null)).isTrue();
        }
    }

    @DisplayName("같은 충돌이 다시 발견되면, ")
    @Nested
    class DetectedAgain {
        @DisplayName("마지막 발견 시각만 갱신되고 처음 발견 시각과 내용은 그대로다.")
        @Test
        void updatesOnlyLastDetectedAt() throws InterruptedException {
            // arrange
            PaymentConflict conflict = failedButPgSucceeded();
            ZonedDateTime first = conflict.getFirstDetectedAt();
            // 시각 해상도 안에서 같은 값이 나오지 않게 아주 짧게 띄운다.
            Thread.sleep(5);

            // act
            conflict.detectedAgain();

            // assert
            assertAll(
                () -> assertThat(conflict.getFirstDetectedAt()).isEqualTo(first),
                () -> assertThat(conflict.getLastDetectedAt()).isAfter(first),
                () -> assertThat(conflict.getObservedStatus()).isEqualTo(PaymentObservation.Status.SUCCESS)
            );
        }
    }
}
