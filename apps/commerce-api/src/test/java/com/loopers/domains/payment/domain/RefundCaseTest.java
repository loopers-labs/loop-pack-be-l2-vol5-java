package com.loopers.domains.payment.domain;

import com.loopers.common.domain.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RefundCaseTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRED_AT = STARTED_AT.plus(Duration.ofMinutes(2));
    private static final Instant LATE = EXPIRED_AT.plus(Duration.ofMinutes(10));

    @Test
    @DisplayName("만료 직전 PG 상태 PENDING·거래 키 TR-1인 결제로 대상을 열면 TRACKING이고 직전 상태·키를 복사하며 이관은 2일 뒤·약속은 4일 뒤·첫 조회는 즉시다")
    void opensTrackingCaseOnExpiry() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.PENDING, "TR-1");

        RefundCase refundCase = RefundCase.openOnExpiry(payment, EXPIRED_AT);

        assertThat(refundCase.getOrderId()).isEqualTo(42);
        assertThat(refundCase.getProviderId()).isEqualTo("test-pg");
        assertThat(refundCase.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(refundCase.getAmount()).isEqualTo(new Money(7000));
        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.PENDING);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getOpenedAt()).isEqualTo(EXPIRED_AT);
        assertThat(refundCase.getHandoffAt()).isEqualTo(EXPIRED_AT.plus(Duration.ofDays(2)));
        assertThat(refundCase.getPromiseAt()).isEqualTo(EXPIRED_AT.plus(Duration.ofDays(4)));
        assertThat(refundCase.getNextCheckAt()).isEqualTo(EXPIRED_AT);
        assertThat(refundCase.getCheckAttempts()).isZero();
        assertThat(refundCase.getRefundRequestedAt()).isNull();
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.getReason()).isNull();
    }

    @Test
    @DisplayName("거래 키 없이 결과 모름(UNKNOWN)으로 만료된 결제는 직전 상태 UNKNOWN·키 없음으로 대상을 연다")
    void opensCaseWithoutKeyForUnknownPayment() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.UNKNOWN, null);

        RefundCase refundCase = RefundCase.openOnExpiry(payment, EXPIRED_AT);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.UNKNOWN);
        assertThat(refundCase.getTransactionKey()).isNull();
    }

    @Test
    @DisplayName("추적 중 대상에 늦은 성공 키 TR-9가 합류하면 키를 저장하고 다음 조회를 지금으로 당기며 상태·기한·조회 횟수는 유지한다")
    void joinsLateSuccessIntoTrackingCase() {
        RefundCase refundCase = existing(RefundCase.Status.TRACKING, null, LATE.plus(Duration.ofHours(1)), 3);

        refundCase.joinLateSuccess("TR-9", LATE);

        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE);
        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        assertThat(refundCase.getHandoffAt()).isEqualTo(EXPIRED_AT.plus(Duration.ofDays(2)));
        assertThat(refundCase.getCheckAttempts()).isEqualTo(3);
    }

    @ParameterizedTest(name = "결론 상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"REFUNDED", "HANDED_TO_HUMAN"})
    @DisplayName("결론 난 대상에는 늦은 성공이 합류해도 키·다음 조회·상태가 바뀌지 않는다")
    void ignoresLateSuccessForConcludedCase(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", null, 3);

        refundCase.joinLateSuccess("TR-1", LATE);

        assertThat(refundCase.getStatus()).isEqualTo(status);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getNextCheckAt()).isNull();
    }

    @ParameterizedTest(name = "환불 진행 상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("환불 요청 뒤 상태의 대상에 늦은 성공 키 TR-9가 합류하면 키만 저장하고 상태·다음 조회는 그대로다")
    void storesKeyOnlyForRefundInProgress(RefundCase.Status status) {
        RefundCase refundCase = existing(status, null, LATE.plus(Duration.ofHours(1)), 3);

        refundCase.joinLateSuccess("TR-9", LATE);

        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getStatus()).isEqualTo(status);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(Duration.ofHours(1)));
        assertThat(refundCase.getConcludedAt()).isNull();
    }

    @Test
    @DisplayName("돈 안 나감으로 닫은 대상에 늦은 성공이 오면 결과가 뒤집힌 것이라 사유 RESULT_REVERSED로 사람에게 넘기고 키·결론 시각을 남긴다")
    void handsClosedCaseToHumanOnLateSuccess() {
        RefundCase refundCase = existing(RefundCase.Status.CLOSED_NO_CHARGE, null, null, 3);

        refundCase.joinLateSuccess("TR-9", LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCase.getReason()).isEqualTo(RefundCase.RESULT_REVERSED);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
    }

    @Test
    @DisplayName("대상 기록 없이 만료된 주문의 늦은 성공은 지금을 연 시각으로 TRACKING 대상을 새로 열고 키를 저장하며 직전 상태는 알 수 없어 비운다")
    void opensCaseForLateSuccessWithoutCase() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.PENDING, null);
        payment.expire();

        RefundCase refundCase = RefundCase.openOnLateSuccess(payment, "TR-9", LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getPriorProviderStatus()).isNull();
        assertThat(refundCase.getOpenedAt()).isEqualTo(LATE);
        assertThat(refundCase.getHandoffAt()).isEqualTo(LATE.plus(Duration.ofDays(2)));
        assertThat(refundCase.getPromiseAt()).isEqualTo(LATE.plus(Duration.ofDays(4)));
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("PG가 FAILED라 했던 결제의 늦은 성공은 자동 환불 없이 바로 사람에게 넘기고 사유 RESULT_REVERSED·결론 시각·키를 남기며 다음 조회는 없다")
    void handsReversedResultToHuman() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.FAILED, "TR-1");

        RefundCase refundCase = RefundCase.handToHumanOnReversal(payment, "TR-1", LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCase.getReason()).isEqualTo(RefundCase.RESULT_REVERSED);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.FAILED);
        assertThat(refundCase.getOpenedAt()).isEqualTo(LATE);
        assertThat(refundCase.getHandoffAt()).isEqualTo(LATE);
        assertThat(refundCase.getPromiseAt()).isEqualTo(LATE.plus(Duration.ofDays(4)));
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
    }

    private static PaymentRecord pending(PaymentRecord.ProviderStatus providerStatus, String transactionKey) {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), "attempt", "test-pg", STARTED_AT);
        payment.applyProviderReport(providerStatus, transactionKey);
        return payment;
    }

    private static RefundCase existing(RefundCase.Status status, String transactionKey, Instant nextCheckAt, int checkAttempts) {
        return RefundCase.restore(42, "test-pg", "LP-ORD-0000000042", new Money(7000), status,
            PaymentRecord.ProviderStatus.UNKNOWN, transactionKey, EXPIRED_AT, EXPIRED_AT.plus(Duration.ofDays(2)),
            EXPIRED_AT.plus(Duration.ofDays(4)), nextCheckAt, checkAttempts, null, null, null);
    }
}
