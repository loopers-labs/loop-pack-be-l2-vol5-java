package com.loopers.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("PaymentConflict 는 결제당 마지막 충돌 요약 하나를 새 충돌로 교체한다.")
class PaymentConflictModelTest {

    @DisplayName("새 충돌은 코드·근거·시각을 모두 교체하고 이전 근거를 남기지 않는다.")
    @Test
    void replacesSummary() {
        ZonedDateTime first = ZonedDateTime.parse("2026-10-23T00:00:00Z");
        PaymentConflictModel conflict = PaymentConflictModel.first(7L, PaymentConflictCode.IDENTITY_MISMATCH,
            new ConflictEvidence("LP-ORD-0000000099", "tx-B", 1L, PgTransactionStatus.SUCCESS, null), first);

        conflict.record(PaymentConflictCode.MULTIPLE_TRANSACTIONS, ConflictEvidence.ofCount(3), first.minusSeconds(5));

        assertAll(
            () -> assertThat(conflict.getPaymentId()).isEqualTo(7L),
            () -> assertThat(conflict.getCode()).isEqualTo(PaymentConflictCode.MULTIPLE_TRANSACTIONS),
            () -> assertThat(conflict.getObservedCount()).isEqualTo(3),
            () -> assertThat(conflict.getObservedOrderId()).isNull(),
            () -> assertThat(conflict.getObservedTransactionKey()).isNull(),
            () -> assertThat(conflict.getObservedAmount()).isNull(),
            () -> assertThat(conflict.getObservedStatus()).isNull(),
            () -> assertThat(conflict.getDetectedAt()).as("시각으로 교체를 막지 않음").isEqualTo(first.minusSeconds(5))
        );
    }
}
