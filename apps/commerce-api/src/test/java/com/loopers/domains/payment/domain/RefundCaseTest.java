package com.loopers.domains.payment.domain;

import com.loopers.common.domain.Money;
import com.loopers.common.error.RuleViolationException;
import com.loopers.domains.payment.domain.RefundCase.MoneyStatus;
import com.loopers.domains.payment.domain.RefundCase.Owner;
import com.loopers.domains.payment.domain.RefundCase.RefundActor;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 두 축 모델(10주차 5b 계획 2·3절)의 전이 표를 실제 엔티티로 본다. 지연은 호출자가 고른 값을 그대로 받으므로
// 상한과 다른 값(DELAY)을 넘겨 도메인이 받은 지연으로 Instant를 계산하는지 확인한다(계획 3-1, S10 도메인 부분).
class RefundCaseTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRED_AT = STARTED_AT.plus(Duration.ofMinutes(2));
    private static final Instant LATE = EXPIRED_AT.plus(Duration.ofMinutes(10));
    private static final Duration DELAY = Duration.ofSeconds(37);

    @Test
    @DisplayName("만료 직전 PG 상태 PENDING·거래 키 TR-1인 결제로 대상을 열면 확인 중·자동 담당이고 직전 상태·키를 복사하며 이관은 2일 뒤·약속은 4일 뒤·첫 조회는 받은 지연 뒤다")
    void opensCheckingCaseOnExpiry() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.PENDING, "TR-1");

        RefundCase refundCase = RefundCase.openOnExpiry(payment, EXPIRED_AT, DELAY);

        assertThat(refundCase.getOrderId()).isEqualTo(42);
        assertThat(refundCase.getProviderId()).isEqualTo("test-pg");
        assertThat(refundCase.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(refundCase.getAmount()).isEqualTo(new Money(7000));
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getOwnerReason()).isNull();
        assertThat(refundCase.getOwnerChangedAt()).isNull();
        assertThat(refundCase.getRefundActor()).isNull();
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.PENDING);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getOpenedAt()).isEqualTo(EXPIRED_AT);
        assertThat(refundCase.getHandoffAt()).isEqualTo(EXPIRED_AT.plus(Duration.ofDays(2)));
        assertThat(refundCase.getPromiseAt()).isEqualTo(EXPIRED_AT.plus(Duration.ofDays(4)));
        assertThat(refundCase.getNextCheckAt()).isEqualTo(EXPIRED_AT.plus(DELAY));
        assertThat(refundCase.getCheckAttempts()).isZero();
        assertThat(refundCase.getRefundRequestedAt()).isNull();
        assertThat(refundCase.getConcludedAt()).isNull();
    }

    @Test
    @DisplayName("거래 키 없이 결과 모름(UNKNOWN)으로 만료된 결제는 직전 상태 UNKNOWN·키 없음으로 확인 중 대상을 연다")
    void opensCaseWithoutKeyForUnknownPayment() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.UNKNOWN, null);

        RefundCase refundCase = RefundCase.openOnExpiry(payment, EXPIRED_AT, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.UNKNOWN);
        assertThat(refundCase.getTransactionKey()).isNull();
    }

    @Test
    @DisplayName("대상 기록 없이 만료된 주문의 늦은 성공은 지금을 연 시각으로 확인 중·자동 담당 대상을 새로 열고 키를 저장하며 조회를 지금으로 당긴다")
    void opensCaseForLateSuccessWithoutCase() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.PENDING, null);
        payment.expire();

        RefundCase refundCase = RefundCase.openOnLateSuccess(payment, "TR-9", LATE);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getPriorProviderStatus()).isNull();
        assertThat(refundCase.getOpenedAt()).isEqualTo(LATE);
        assertThat(refundCase.getHandoffAt()).isEqualTo(LATE.plus(Duration.ofDays(2)));
        assertThat(refundCase.getPromiseAt()).isEqualTo(LATE.plus(Duration.ofDays(4)));
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("PG가 FAILED라 했던 결제의 늦은 성공은 청구 확인·사람 담당(RESULT_REVERSED)으로 열고 돈은 끝나지 않았으므로 받은 지연 뒤 조회를 잡는다")
    void opensReversedResultAsChargedForPerson() {
        PaymentRecord payment = pending(PaymentRecord.ProviderStatus.FAILED, "TR-1");

        RefundCase refundCase = RefundCase.reverseOnLateSuccess(payment, "TR-1", LATE, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.RESULT_REVERSED);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(LATE);
        assertThat(refundCase.getOwnerClosedAt()).isNull();
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.FAILED);
        assertThat(refundCase.getOpenedAt()).isEqualTo(LATE);
        assertThat(refundCase.getHandoffAt()).isEqualTo(LATE);
        assertThat(refundCase.getPromiseAt()).isEqualTo(LATE.plus(Duration.ofDays(4)));
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
        assertThat(refundCase.canRequestRefund()).isFalse();
    }

    @ParameterizedTest(name = "담당={0}")
    @EnumSource(Owner.class)
    @DisplayName("확인 중 대상에 늦은 성공 키 TR-9가 합류하면 키를 저장하고 다음 조회를 지금으로 당기며 돈·담당·기한·조회 횟수는 유지한다")
    void joinsLateSuccessIntoCheckingCase(Owner owner) {
        RefundCase refundCase = existing(MoneyStatus.CHECKING, owner, null, LATE.plus(Duration.ofHours(1)), 3);

        refundCase.joinLateSuccess("TR-9", LATE, DELAY);

        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(owner);
        assertThat(refundCase.getHandoffAt()).isEqualTo(EXPIRED_AT.plus(Duration.ofDays(2)));
        assertThat(refundCase.getCheckAttempts()).isEqualTo(3);
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"CHARGED", "REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("청구 확인·환불 요청 뒤 대상에 늦은 성공 키 TR-9가 합류하면 키만 저장하고 돈·담당·다음 조회는 그대로다")
    void storesKeyOnlyAfterCharged(MoneyStatus moneyStatus) {
        RefundCase refundCase = existing(moneyStatus, Owner.AUTO, null, LATE.plus(Duration.ofHours(1)), 3);

        refundCase.joinLateSuccess("TR-9", LATE, DELAY);

        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(Duration.ofHours(1)));
        assertThat(refundCase.getConcludedAt()).isNull();
    }

    @Test
    @DisplayName("환불됨 대상에는 늦은 성공이 합류해도 키·다음 조회·돈·담당이 바뀌지 않는다")
    void ignoresLateSuccessForRefundedCase() {
        RefundCase refundCase = concluded(MoneyStatus.REFUNDED, RefundActor.SYSTEM, Owner.AUTO);

        refundCase.joinLateSuccess("TR-OTHER", LATE, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getNextCheckAt()).isNull();
    }

    @ParameterizedTest(name = "앞선 담당={0}")
    @EnumSource(Owner.class)
    @DisplayName("청구 없음으로 끝난 대상에 늦은 성공이 오면 사실이 뒤집힌 것이라 청구 확인·사람 담당(RESULT_REVERSED)으로 다시 열고 결론 시각·담당 종료를 비운다")
    void reopensNoChargeCaseAsChargedForPerson(Owner priorOwner) {
        RefundCase refundCase = concluded(MoneyStatus.NO_CHARGE, null, priorOwner);

        refundCase.joinLateSuccess("TR-9", LATE, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.RESULT_REVERSED);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(LATE);
        assertThat(refundCase.getOwnerClosedAt()).isNull();
        assertThat(refundCase.getOwnerClosedBy()).isNull();
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.isConcluded()).isFalse();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
    }

    @Test
    @DisplayName("확인 중 대상에 조회로 찾은 키 TR-9로 청구를 기록하면 청구 확인이 되고 키를 남긴다")
    void recordsCharged() {
        RefundCase refundCase = existing(MoneyStatus.CHECKING, Owner.AUTO, null, LATE, 2);

        refundCase.recordCharged("TR-9");

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = "CHECKING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("확인 중이 아닌 대상에는 청구를 다시 기록할 수 없어 예외가 나고 돈의 상태가 그대로다")
    void rejectsChargedOutsideChecking(MoneyStatus moneyStatus) {
        RefundCase refundCase = existing(moneyStatus, Owner.AUTO, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.recordCharged("TR-1")).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
    }

    @Test
    @DisplayName("청구 확인·자동 담당 대상에 환불을 요청하면 환불 요청함이 되고 요청 시각을 남기며 결과 확인은 받은 지연 뒤로 잡는다")
    void requestsRefundFromChargedAuto() {
        RefundCase refundCase = existing(MoneyStatus.CHARGED, Owner.AUTO, "TR-9", LATE, 2);
        assertThat(refundCase.canRequestRefund()).isTrue();

        refundCase.requestRefund(LATE, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_REQUESTED);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCase.getRefundRequestedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.canRequestRefund()).isFalse();
    }

    @Test
    @DisplayName("사람 담당인 청구 확인 대상은 환불을 요청할 수 없어 예외가 나고 돈의 상태·요청 시각이 그대로다(자동 환불은 자동 담당만)")
    void rejectsRefundRequestForPerson() {
        RefundCase refundCase = existing(MoneyStatus.CHARGED, Owner.PERSON, "TR-1", LATE, 1);

        assertThat(refundCase.canRequestRefund()).isFalse();
        assertThatThrownBy(() -> refundCase.requestRefund(LATE, DELAY)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getRefundRequestedAt()).isNull();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = "CHARGED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("청구 확인이 아닌 대상에는 환불을 요청할 수 없어 예외가 나고 돈의 상태·요청 시각이 그대로다(환불 요청은 한 번만)")
    void rejectsRefundRequestOutsideCharged(MoneyStatus moneyStatus) {
        RefundCase refundCase = existing(moneyStatus, Owner.AUTO, "TR-1", LATE, 1);
        Instant requestedBefore = refundCase.getRefundRequestedAt();

        assertThat(refundCase.canRequestRefund()).isFalse();
        assertThatThrownBy(() -> refundCase.requestRefund(LATE, DELAY)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getRefundRequestedAt()).isEqualTo(requestedBefore);
    }

    @ParameterizedTest(name = "돈의 상태={0} → 환불 주체 {1}")
    @CsvSource({"REFUND_REQUESTED, SYSTEM", "REFUND_UNKNOWN, SYSTEM", "CHECKING, EXTERNAL", "CHARGED, EXTERNAL"})
    @DisplayName("PG에서 취소가 확인되면 환불됨으로 끝나고 우리가 요청한 뒤면 주체 SYSTEM, 요청한 적 없으면 EXTERNAL이며 결론 시각을 남기고 다음 조회는 없다")
    void marksRefundedWithActor(MoneyStatus moneyStatus, RefundActor expectedActor) {
        RefundCase refundCase = existing(moneyStatus, Owner.AUTO, "TR-1", LATE, 1);

        refundCase.markRefunded(LATE);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase.getRefundActor()).isEqualTo(expectedActor);
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
        assertThat(refundCase.isConcluded()).isTrue();
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getOwnerClosedAt()).isNull();
        assertThat(refundCase.getOwnerClosedBy()).isNull();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"CHECKING", "CHARGED", "REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("사람 담당 중인 대상이 PG 취소 확인으로 환불됨이 되면 담당을 SYSTEM이 같은 때에 자동 종료한다(담당 값·사유는 남는다)")
    void closesPersonOwnerWhenRefunded(MoneyStatus moneyStatus) {
        RefundCase refundCase = personOpen(moneyStatus);

        refundCase.markRefunded(LATE);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(refundCase.getOwnerClosedAt()).isEqualTo(LATE);
        assertThat(refundCase.getOwnerClosedBy()).isEqualTo(RefundCase.CLOSED_BY_SYSTEM);
        assertThat(refundCase.isPersonOpen()).isFalse();
    }

    @ParameterizedTest(name = "끝난 돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUNDED", "NO_CHARGE"})
    @DisplayName("이미 끝난 돈의 상태는 환불됨·청구 없음으로 다시 바꿀 수 없어 예외가 나고 값이 그대로다(종결 돈 칸 되돌림 금지)")
    void rejectsChangingConcludedMoney(MoneyStatus moneyStatus) {
        RefundCase refundCase = concluded(moneyStatus, moneyStatus == MoneyStatus.REFUNDED ? RefundActor.SYSTEM : null, Owner.AUTO);

        assertThatThrownBy(() -> refundCase.markRefunded(LATE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refundCase.closeNoCharge(LATE)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getConcludedAt()).isEqualTo(EXPIRED_AT);
    }

    @Test
    @DisplayName("취소 결과를 모르면 환불 결과 모름이 되고 결론 없이 받은 지연 뒤 조회로 확인한다")
    void marksRefundUnknown() {
        RefundCase refundCase = existing(MoneyStatus.REFUND_REQUESTED, Owner.AUTO, "TR-1", LATE, 1);

        refundCase.markRefundUnknown(LATE, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.isConcluded()).isFalse();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = "REFUND_REQUESTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("환불 요청함이 아닌 대상은 결과 모름으로 바꿀 수 없어 예외가 난다")
    void rejectsRefundUnknownOutsideRequested(MoneyStatus moneyStatus) {
        RefundCase refundCase = existing(moneyStatus, Owner.AUTO, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.markRefundUnknown(LATE, DELAY)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
    }

    @Test
    @DisplayName("PG가 효과 없이 취소를 거절하면 돈은 청구 확인으로 돌아가고 담당만 사람(REFUND_REJECTED)이 되며 요청 시각은 남는다")
    void marksRefundRejected() {
        RefundCase refundCase = existing(MoneyStatus.REFUND_REQUESTED, Owner.AUTO, "TR-1", LATE, 1);

        refundCase.markRefundRejected(LATE, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.REFUND_REJECTED);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(LATE);
        assertThat(refundCase.getRefundRequestedAt()).isEqualTo(EXPIRED_AT.plusSeconds(30));
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
        assertThat(refundCase.canRequestRefund()).isFalse();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = "REFUND_REQUESTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("환불 요청함이 아닌 대상은 취소 거절을 반영할 수 없어 예외가 나고 돈·담당이 그대로다")
    void rejectsRefundRejectedOutsideRequested(MoneyStatus moneyStatus) {
        RefundCase refundCase = existing(moneyStatus, Owner.AUTO, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.markRefundRejected(LATE, DELAY)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
    }

    @Test
    @DisplayName("확인 중 대상이 PG에서 청구 없음으로 확인되면 청구 없음으로 끝나고 다음 조회는 없다")
    void closesNoCharge() {
        RefundCase refundCase = existing(MoneyStatus.CHECKING, Owner.AUTO, "TR-1", LATE, 1);

        refundCase.closeNoCharge(LATE);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
        assertThat(refundCase.getRefundActor()).isNull();
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
        assertThat(refundCase.isConcluded()).isTrue();
        assertThat(refundCase.getOwnerClosedAt()).isNull();
    }

    @Test
    @DisplayName("사람 담당 중인 확인 중 대상이 PG에서 청구 없음으로 확인되면 담당을 SYSTEM이 같은 때에 자동 종료한다")
    void closesPersonOwnerWhenNoCharge() {
        RefundCase refundCase = personOpen(MoneyStatus.CHECKING);

        refundCase.closeNoCharge(LATE);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
        assertThat(refundCase.getOwnerClosedAt()).isEqualTo(LATE);
        assertThat(refundCase.getOwnerClosedBy()).isEqualTo(RefundCase.CLOSED_BY_SYSTEM);
        assertThat(refundCase.isPersonOpen()).isFalse();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = "CHECKING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("확인 중이 아닌 대상(청구 확인·환불 요청 뒤·종결)은 청구 없음으로 닫을 수 없어 예외가 난다")
    void rejectsNoChargeOutsideChecking(MoneyStatus moneyStatus) {
        RefundCase refundCase = existing(moneyStatus, Owner.PERSON, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.closeNoCharge(LATE)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"CHECKING", "CHARGED", "REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("끝나지 않은 자동 담당 대상을 사람에게 넘기면 돈의 상태는 그대로이고 담당만 사람·사유·바뀐 시각이 남으며 받은 지연 뒤 조회를 계속한다")
    void assignsPersonKeepingMoney(MoneyStatus moneyStatus) {
        RefundCase refundCase = existing(moneyStatus, Owner.AUTO, "TR-1", LATE, 1);

        refundCase.assignPerson(RefundCase.HANDOFF_DEADLINE, LATE, DELAY);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(LATE);
        assertThat(refundCase.getOwnerClosedAt()).isNull();
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
        assertThat(refundCase.isConcluded()).isFalse();
    }

    @Test
    @DisplayName("이미 사람 담당인 대상은 다시 넘길 수 없어 예외가 나고 처음 사유·바뀐 시각이 그대로다")
    void rejectsAssigningPersonTwice() {
        RefundCase refundCase = personOpen(MoneyStatus.REFUND_REQUESTED);

        assertThatThrownBy(() -> refundCase.assignPerson(RefundCase.TRANSACTION_MISMATCH, LATE, DELAY))
            .isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(EXPIRED_AT);
    }

    @ParameterizedTest(name = "끝난 돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUNDED", "NO_CHARGE"})
    @DisplayName("돈이 끝난 대상은 사람에게 넘기거나 조회 일정을 바꿀 수 없어 예외가 나며 값이 그대로다")
    void keepsConcludedCase(MoneyStatus moneyStatus) {
        RefundCase refundCase = concluded(moneyStatus, null, Owner.AUTO);

        assertThat(refundCase.isConcluded()).isTrue();
        assertThatThrownBy(() -> refundCase.assignPerson(RefundCase.HANDOFF_DEADLINE, LATE, DELAY))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refundCase.recordInconclusiveCheck(LATE, DELAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refundCase.postponeCheck(LATE, DELAY)).isInstanceOf(IllegalStateException.class);

        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getNextCheckAt()).isNull();
        assertThat(refundCase.getCheckAttempts()).isEqualTo(1);
    }

    @ParameterizedTest(name = "돈의 상태={0}, 담당={1}")
    @CsvSource({"CHECKING, AUTO", "CHARGED, PERSON", "REFUND_REQUESTED, AUTO", "REFUND_UNKNOWN, PERSON"})
    @DisplayName("결론 없는 조회를 기록하면 조회 횟수가 1 늘고 다음 조회는 받은 지연 뒤이며 돈·담당은 그대로다")
    void recordsInconclusiveCheckWithGivenDelay(MoneyStatus moneyStatus, Owner owner) {
        RefundCase refundCase = existing(moneyStatus, owner, "TR-1", LATE, 4);

        refundCase.recordInconclusiveCheck(LATE, DELAY);

        assertThat(refundCase.getCheckAttempts()).isEqualTo(5);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getOwner()).isEqualTo(owner);
    }

    @Test
    @DisplayName("조회 회로 때문에 조회하지 못하면 조회 횟수는 그대로이고 받은 지연만큼 다음 조회를 미룬다")
    void postponesWithoutCountingAttempt() {
        RefundCase refundCase = existing(MoneyStatus.REFUND_UNKNOWN, Owner.AUTO, "TR-1", LATE, 3);

        refundCase.postponeCheck(LATE, DELAY);

        assertThat(refundCase.getCheckAttempts()).isEqualTo(3);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(DELAY));
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
    }

    @ParameterizedTest(name = "조회 전 횟수={0} → 상한 {1}분")
    @CsvSource({"0, 1", "1, 2", "2, 4", "5, 32", "6, 60", "20, 60", "40, 60"})
    @DisplayName("자동 담당의 결론 없는 조회 뒤 상한은 1분에서 두 배씩 늘다가 1시간에서 멈춘다")
    void backsOffInconclusiveCeiling(int attemptsBefore, long expectedMinutes) {
        RefundCase refundCase = existing(MoneyStatus.CHECKING, Owner.AUTO, null, LATE, attemptsBefore);

        assertThat(refundCase.inconclusiveCheckCeiling()).isEqualTo(Duration.ofMinutes(expectedMinutes));
    }

    @ParameterizedTest(name = "조회 횟수={0} → 미룬 상한 {1}분")
    @CsvSource({"0, 1", "1, 1", "3, 4", "7, 60"})
    @DisplayName("자동 담당이 회로로 미룰 때 상한은 마지막 상한이다")
    void postponeCeilingIsLastCeiling(int attempts, long expectedMinutes) {
        RefundCase refundCase = existing(MoneyStatus.REFUND_UNKNOWN, Owner.AUTO, "TR-1", LATE, attempts);

        assertThat(refundCase.postponeCheckCeiling()).isEqualTo(Duration.ofMinutes(expectedMinutes));
    }

    @ParameterizedTest(name = "조회 횟수={0}")
    @CsvSource({"0", "3", "40"})
    @DisplayName("사람 담당 중인 대상의 다음 조회 상한은 조회 횟수와 무관하게 30분이다")
    void personCeilingIsThirtyMinutes(int attempts) {
        RefundCase refundCase = existing(MoneyStatus.CHARGED, Owner.PERSON, "TR-1", LATE, attempts);

        assertThat(refundCase.inconclusiveCheckCeiling()).isEqualTo(Duration.ofMinutes(30));
        assertThat(refundCase.postponeCheckCeiling()).isEqualTo(Duration.ofMinutes(30));
    }

    @ParameterizedTest(name = "지금=이관 기한{0}초")
    @CsvSource({"-1, false", "0, true", "1, true"})
    @DisplayName("끝나지 않은 자동 담당 대상은 지금이 이관 기한(연 시각+2일)과 같거나 지나면 사람에게 넘길 때다")
    void detectsHandoffDeadline(long offsetSeconds, boolean expected) {
        RefundCase refundCase = existing(MoneyStatus.REFUND_REQUESTED, Owner.AUTO, null, LATE, 3);
        Instant handoffAt = EXPIRED_AT.plus(Duration.ofDays(2));

        assertThat(refundCase.isHandoffDue(handoffAt.plusSeconds(offsetSeconds))).isEqualTo(expected);
    }

    @Test
    @DisplayName("돈이 끝났거나 이미 사람 담당인 대상은 이관 기한이 지나도 다시 넘길 때가 아니다")
    void concludedOrPersonCaseIsNeverHandoffDue() {
        Instant afterDeadline = EXPIRED_AT.plus(Duration.ofDays(3));

        assertThat(concluded(MoneyStatus.REFUNDED, RefundActor.SYSTEM, Owner.AUTO).isHandoffDue(afterDeadline)).isFalse();
        assertThat(personOpen(MoneyStatus.CHECKING).isHandoffDue(afterDeadline)).isFalse();
    }

    @ParameterizedTest(name = "돈의 상태={0}, 기록={1}")
    @CsvSource({"CHECKING, REFUNDED", "CHARGED, REFUNDED", "REFUND_REQUESTED, REFUNDED", "REFUND_UNKNOWN, REFUNDED",
        "CHECKING, NO_CHARGE", "CHARGED, NO_CHARGE"})
    @DisplayName("S5: 사람 담당 중인 대상에 관리자가 환불함을 적으면(끝나지 않은 4값) 환불됨(PERSON), 확인 중·청구 확인에 청구 없음을 적으면 청구 없음으로 끝나고 담당 사유는 남은 채 그 관리자 이름으로 닫히며 조회를 멈춘다")
    void personResolutionConcludesAndClosesOwner(MoneyStatus before, RefundCase.PersonResult result) {
        RefundCase refundCase = personOpen(before);

        refundCase.resolveByPerson(result, "ops-admin", LATE);

        boolean refunded = result == RefundCase.PersonResult.REFUNDED;
        assertThat(refundCase.getMoneyStatus()).isEqualTo(refunded ? MoneyStatus.REFUNDED : MoneyStatus.NO_CHARGE);
        assertThat(refundCase.getRefundActor()).isEqualTo(refunded ? RefundActor.PERSON : null);
        assertThat(refundCase.getConcludedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isNull();
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(refundCase.getOwnerClosedAt()).isEqualTo(LATE);
        assertThat(refundCase.getOwnerClosedBy()).isEqualTo("ops-admin");
        assertThat(refundCase.isPersonOpen()).isFalse();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("S5: 우리가 환불을 요청한 뒤인 사람 담당 대상(환불 요청함·결과 모름)에 청구 없음을 적으면 취소를 보냈을 수 있는 돈이라 규칙 위반이고 돈·담당이 그대로다")
    void rejectsNoChargeAfterRefundRequested(MoneyStatus before) {
        RefundCase refundCase = personOpen(before);

        assertThatThrownBy(() -> refundCase.resolveByPerson(RefundCase.PersonResult.NO_CHARGE, "ops-admin", LATE))
            .isInstanceOf(RuleViolationException.class);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(before);
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.isPersonOpen()).isTrue();
    }

    @ParameterizedTest(name = "기록={0}")
    @EnumSource(RefundCase.PersonResult.class)
    @DisplayName("S5: 자동 담당 대상에는 사람 기록을 적을 수 없어 규칙 위반이고 돈·담당이 그대로다")
    void rejectsPersonResolutionOnAutoOwner(RefundCase.PersonResult result) {
        RefundCase refundCase = existing(MoneyStatus.CHECKING, Owner.AUTO, null, LATE, 1);

        assertThatThrownBy(() -> refundCase.resolveByPerson(result, "ops-admin", LATE))
            .isInstanceOf(RuleViolationException.class);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwnerClosedAt()).isNull();
    }

    @ParameterizedTest(name = "끝난 돈={0}, 기록={1}")
    @CsvSource({"REFUNDED, REFUNDED", "REFUNDED, NO_CHARGE", "NO_CHARGE, REFUNDED", "NO_CHARGE, NO_CHARGE"})
    @DisplayName("S5: 돈이 이미 끝나 담당이 닫힌 대상에는 사람 기록을 적을 수 없어 규칙 위반이고 처음 결론·종료 주체가 그대로다")
    void rejectsPersonResolutionAfterConclusion(MoneyStatus concludedStatus, RefundCase.PersonResult result) {
        RefundCase refundCase = concluded(concludedStatus,
            concludedStatus == MoneyStatus.REFUNDED ? RefundActor.EXTERNAL : null, Owner.PERSON);

        assertThatThrownBy(() -> refundCase.resolveByPerson(result, "ops-admin", LATE))
            .isInstanceOf(RuleViolationException.class);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(concludedStatus);
        assertThat(refundCase.getOwnerClosedBy()).isEqualTo(RefundCase.CLOSED_BY_SYSTEM);
        assertThat(refundCase.getConcludedAt()).isEqualTo(EXPIRED_AT);
    }

    @Test
    @DisplayName("S5: 담당이 열린 채 돈만 끝난 기록(모순 행)에도 사람 기록은 규칙 위반이다")
    void rejectsPersonResolutionOnConcludedMoneyEvenIfOwnerOpen() {
        RefundCase refundCase = existing(MoneyStatus.NO_CHARGE, Owner.PERSON, "TR-1", LATE, 1);

        assertThatThrownBy(() -> refundCase.resolveByPerson(RefundCase.PersonResult.REFUNDED, "ops-admin", LATE))
            .isInstanceOf(RuleViolationException.class);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
    }

    private static PaymentRecord pending(PaymentRecord.ProviderStatus providerStatus, String transactionKey) {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), "attempt", "test-pg", STARTED_AT);
        payment.applyProviderReport(providerStatus, transactionKey);
        return payment;
    }

    private static RefundCase existing(MoneyStatus moneyStatus, Owner owner, String transactionKey, Instant nextCheckAt,
        int checkAttempts) {
        Instant requestedAt = moneyStatus == MoneyStatus.REFUND_REQUESTED || moneyStatus == MoneyStatus.REFUND_UNKNOWN
            ? EXPIRED_AT.plusSeconds(30) : null;
        boolean concluded = moneyStatus == MoneyStatus.REFUNDED || moneyStatus == MoneyStatus.NO_CHARGE;
        return RefundCase.restore(42, "test-pg", "LP-ORD-0000000042", new Money(7000), moneyStatus,
            moneyStatus == MoneyStatus.REFUNDED ? RefundActor.SYSTEM : null, owner,
            owner == Owner.PERSON ? RefundCase.HANDOFF_DEADLINE : null, owner == Owner.PERSON ? EXPIRED_AT : null, null, null,
            PaymentRecord.ProviderStatus.UNKNOWN, transactionKey, EXPIRED_AT, EXPIRED_AT.plus(Duration.ofDays(2)),
            EXPIRED_AT.plus(Duration.ofDays(4)), concluded ? null : nextCheckAt, checkAttempts, requestedAt,
            concluded ? EXPIRED_AT : null);
    }

    private static RefundCase personOpen(MoneyStatus moneyStatus) {
        return existing(moneyStatus, Owner.PERSON, "TR-1", LATE, 1);
    }

    // 사람 담당이었다가 돈이 끝난 대상은 SYSTEM이 담당을 닫은 상태로 둔다.
    private static RefundCase concluded(MoneyStatus moneyStatus, RefundActor refundActor, Owner owner) {
        boolean person = owner == Owner.PERSON;
        return RefundCase.restore(42, "test-pg", "LP-ORD-0000000042", new Money(7000), moneyStatus, refundActor, owner,
            person ? RefundCase.HANDOFF_DEADLINE : null, person ? EXPIRED_AT : null, person ? EXPIRED_AT : null,
            person ? RefundCase.CLOSED_BY_SYSTEM : null, PaymentRecord.ProviderStatus.UNKNOWN, "TR-1", EXPIRED_AT,
            EXPIRED_AT.plus(Duration.ofDays(2)), EXPIRED_AT.plus(Duration.ofDays(4)), null, 1, null, EXPIRED_AT);
    }
}
