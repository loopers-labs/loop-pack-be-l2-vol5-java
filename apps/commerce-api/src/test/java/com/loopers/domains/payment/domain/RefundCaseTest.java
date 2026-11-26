package com.loopers.domains.payment.domain;

import com.loopers.common.domain.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("추적 중 대상에 환불을 요청하면 REFUND_REQUESTED가 되고 조회로 찾은 키·요청 시각을 남기며 결과 확인은 1분 뒤로 잡는다")
    void requestsRefundFromTracking() {
        RefundCase refundCase = existing(RefundCase.Status.TRACKING, null, LATE, 2);

        refundCase.requestRefund("TR-9", LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.REFUND_REQUESTED);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getRefundRequestedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(Duration.ofMinutes(1)));
        assertThat(refundCase.getConcludedAt()).isNull();
    }

    @Test
    @DisplayName("이미 키 TR-1을 아는 추적 대상에 환불을 요청하면 저장된 키를 그대로 둔다")
    void keepsStoredKeyOnRefundRequest() {
        RefundCase refundCase = existing(RefundCase.Status.TRACKING, "TR-1", LATE, 0);

        refundCase.requestRefund("TR-1", LATE);

        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = "TRACKING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("추적 중이 아닌 대상에는 환불을 다시 요청할 수 없어 예외가 나고 상태·요청 시각이 그대로다(환불 요청은 한 번만)")
    void rejectsRefundRequestOutsideTracking(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.requestRefund("TR-1", LATE)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getStatus()).isEqualTo(status);
        assertThat(refundCase.getRefundRequestedAt()).isNull();
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("환불 요청 뒤 상태에서 취소가 확인되면 REFUNDED로 결론 나고 결론 시각을 남기며 다음 조회는 없다")
    void marksRefunded(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", LATE, 1);

        refundCase.markRefunded(LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.REFUNDED);
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
        assertThat(refundCase.isConcluded()).isTrue();
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"TRACKING", "REFUNDED", "CLOSED_NO_CHARGE", "HANDED_TO_HUMAN"})
    @DisplayName("환불을 요청하지 않았거나 이미 결론 난 대상은 환불 완료로 바꿀 수 없어 예외가 난다")
    void rejectsRefundedWithoutRequest(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.markRefunded(LATE)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("취소 결과를 모르면 REFUND_UNKNOWN이 되고 결론 없이 1분 뒤 조회로 확인한다")
    void marksRefundUnknown() {
        RefundCase refundCase = existing(RefundCase.Status.REFUND_REQUESTED, "TR-1", LATE, 1);

        refundCase.markRefundUnknown(LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.REFUND_UNKNOWN);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(Duration.ofMinutes(1)));
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.isConcluded()).isFalse();
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = "REFUND_REQUESTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("환불 요청 직후가 아닌 대상은 결과 모름으로 바꿀 수 없어 예외가 난다")
    void rejectsRefundUnknownOutsideRequested(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.markRefundUnknown(LATE)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("추적 중 대상이 돈 안 나감으로 확인되면 CLOSED_NO_CHARGE로 결론 나고 다음 조회는 없다")
    void closesNoCharge() {
        RefundCase refundCase = existing(RefundCase.Status.TRACKING, "TR-1", LATE, 1);

        refundCase.closeNoCharge(LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.CLOSED_NO_CHARGE);
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
        assertThat(refundCase.isConcluded()).isTrue();
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = "TRACKING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("환불을 요청한 뒤나 결론 난 대상은 돈 안 나감으로 닫을 수 없어 예외가 난다")
    void rejectsCloseOutsideTracking(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.closeNoCharge(LATE)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"TRACKING", "REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("결론 전 대상을 사람에게 넘기면 HANDED_TO_HUMAN으로 결론 나고 사유·결론 시각을 남기며 다음 조회는 없다")
    void handsOffUnconcludedCase(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", LATE, 1);

        refundCase.handOff(RefundCase.HANDOFF_DEADLINE, LATE);

        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCase.getReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
    }

    @ParameterizedTest(name = "결론 상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"REFUNDED", "CLOSED_NO_CHARGE", "HANDED_TO_HUMAN"})
    @DisplayName("결론 난 대상은 결론 상태로 보고 사람에게 다시 넘기거나 조회 일정을 바꿀 수 없어 예외가 나며 값이 그대로다")
    void keepsConcludedCase(RefundCase.Status status) {
        RefundCase refundCase = existing(status, "TR-1", null, 1);

        assertThat(refundCase.isConcluded()).isTrue();
        assertThatThrownBy(() -> refundCase.handOff(RefundCase.HANDOFF_DEADLINE, LATE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refundCase.recordInconclusiveCheck(LATE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refundCase.postponeCheck(LATE)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getStatus()).isEqualTo(status);
        assertThat(refundCase.getNextCheckAt()).isNull();
        assertThat(refundCase.getCheckAttempts()).isEqualTo(1);
    }

    @ParameterizedTest(name = "조회 전 횟수={0} → 다음 간격 {1}분")
    @CsvSource({"0, 1", "1, 2", "2, 4", "5, 32", "6, 60", "20, 60", "40, 60"})
    @DisplayName("결론 없는 조회를 기록하면 조회 횟수가 1 늘고 다음 조회 간격은 1분에서 두 배씩 늘다가 1시간에서 멈춘다")
    void backsOffInconclusiveChecks(int attemptsBefore, long expectedMinutes) {
        RefundCase refundCase = existing(RefundCase.Status.TRACKING, null, LATE, attemptsBefore);

        refundCase.recordInconclusiveCheck(LATE);

        assertThat(refundCase.getCheckAttempts()).isEqualTo(attemptsBefore + 1);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(Duration.ofMinutes(expectedMinutes)));
        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.TRACKING);
    }

    @ParameterizedTest(name = "조회 횟수={0} → 미룬 간격 {1}분")
    @CsvSource({"0, 1", "1, 1", "3, 4", "7, 60"})
    @DisplayName("조회 회로 때문에 조회하지 못하면 조회 횟수는 그대로이고 마지막 간격만큼 다음 조회를 미룬다")
    void postponesWithoutCountingAttempt(int attempts, long expectedMinutes) {
        RefundCase refundCase = existing(RefundCase.Status.REFUND_UNKNOWN, "TR-1", LATE, attempts);

        refundCase.postponeCheck(LATE);

        assertThat(refundCase.getCheckAttempts()).isEqualTo(attempts);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(Duration.ofMinutes(expectedMinutes)));
        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.REFUND_UNKNOWN);
    }

    @ParameterizedTest(name = "지금=이관 기한{0}초")
    @CsvSource({"-1, false", "0, true", "1, true"})
    @DisplayName("결론 전 대상은 지금이 이관 기한(연 시각+2일)과 같거나 지나면 사람에게 넘길 때다")
    void detectsHandoffDeadline(long offsetSeconds, boolean expected) {
        RefundCase refundCase = existing(RefundCase.Status.TRACKING, null, LATE, 3);
        Instant handoffAt = EXPIRED_AT.plus(Duration.ofDays(2));

        assertThat(refundCase.isHandoffDue(handoffAt.plusSeconds(offsetSeconds))).isEqualTo(expected);
    }

    @Test
    @DisplayName("결론 난 대상은 이관 기한이 지나도 다시 넘길 때가 아니다")
    void concludedCaseIsNeverHandoffDue() {
        RefundCase refundCase = existing(RefundCase.Status.REFUNDED, "TR-1", null, 3);

        assertThat(refundCase.isHandoffDue(EXPIRED_AT.plus(Duration.ofDays(3)))).isFalse();
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
