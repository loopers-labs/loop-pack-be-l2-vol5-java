package com.loopers.domains.payment.domain;

import com.loopers.common.domain.Money;
import com.loopers.common.error.RuleViolationException;
import java.time.Duration;
import java.time.Instant;

// 만료된 PG 결제 하나를 환불 결론까지 추적하는 기록이다(10주차 5b 계획 2절). 주문당 결제 기록이 하나라 주문 ID로 식별한다.
// 한 칸 한 뜻: 돈의 상태(money_status)는 PG·사람이 확인한 돈의 사실만, 담당(owner)은 다음 일을 누가 하는지만 담는다.
public final class RefundCase {
    public enum MoneyStatus { CHECKING, CHARGED, REFUND_REQUESTED, REFUND_UNKNOWN, REFUNDED, NO_CHARGE }

    public enum Owner { AUTO, PERSON }

    public enum RefundActor { SYSTEM, PERSON, EXTERNAL }

    public enum PersonResult { REFUNDED, NO_CHARGE }

    // 마지막 확인이 왜 멈췄는지 남기는 유한 분류다(10주차 5c). 결론·진행이 정상으로 처리된 확인은 OK 하나로 둔다(PL P1).
    public enum CheckOutcome {
        PG_UNREACHABLE, PG_ERROR, APPLY_FAILED, MISMATCH, PENDING, EMPTY, CANCEL_UNKNOWN, CANCEL_REJECTED, OK
    }

    // 회차를 누가 돌렸는지다. 스케줄러는 자동 담당만 환불하고, 사람이 "지금 처리"를 누른 회차는 사람 담당 건도 환불할 수 있다(PL P2).
    public enum CheckTrigger { SCHEDULER, PERSON }

    // [임시] 자동 추적 2일 뒤 사람에게 넘기고, 고객에게는 4일 안 환불을 약속한다(사용자 결정 D188).
    public static final Duration HANDOFF_AFTER = Duration.ofDays(2);
    public static final Duration PROMISE_AFTER = Duration.ofDays(4);
    public static final String RESULT_REVERSED = "RESULT_REVERSED";
    public static final String HANDOFF_DEADLINE = "HANDOFF_DEADLINE";
    public static final String TRANSACTION_MISMATCH = "TRANSACTION_MISMATCH";
    public static final String MULTIPLE_TRANSACTIONS = "MULTIPLE_TRANSACTIONS";
    public static final String REFUND_REJECTED = "REFUND_REJECTED";
    public static final String CLOSED_BY_SYSTEM = "SYSTEM";
    // [임시] 다음 조회 지연의 상한이다(계획 3-1). 실제 지연은 호출자가 이 상한 안에서 jitter로 고른다.
    public static final Duration FIRST_CHECK_DELAY = Duration.ofMinutes(1);
    public static final Duration MAX_CHECK_DELAY = Duration.ofHours(1);
    public static final Duration PERSON_CHECK_DELAY = Duration.ofMinutes(30);

    private final long orderId;
    private final String providerId;
    private final String providerOrderId;
    private final Money amount;
    private MoneyStatus moneyStatus;
    private RefundActor refundActor;
    private Owner owner;
    private String ownerReason;
    private Instant ownerChangedAt;
    private Instant ownerClosedAt;
    private String ownerClosedBy;
    private final PaymentRecord.ProviderStatus priorProviderStatus;
    private String transactionKey;
    private final Instant openedAt;
    private final Instant handoffAt;
    private final Instant promiseAt;
    private Instant nextCheckAt;
    private int checkAttempts;
    private Instant refundRequestedAt;
    private Instant concludedAt;
    private Instant lastCheckAt;
    private CheckOutcome lastCheckOutcome;
    private String lastCheckCause;

    private RefundCase(long orderId, String providerId, String providerOrderId, Money amount, MoneyStatus moneyStatus,
        RefundActor refundActor, Owner owner, String ownerReason, Instant ownerChangedAt, Instant ownerClosedAt,
        String ownerClosedBy, PaymentRecord.ProviderStatus priorProviderStatus, String transactionKey, Instant openedAt,
        Instant handoffAt, Instant promiseAt, Instant nextCheckAt, int checkAttempts, Instant refundRequestedAt,
        Instant concludedAt) {
        this.orderId = orderId;
        this.providerId = providerId;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.moneyStatus = moneyStatus;
        this.refundActor = refundActor;
        this.owner = owner;
        this.ownerReason = ownerReason;
        this.ownerChangedAt = ownerChangedAt;
        this.ownerClosedAt = ownerClosedAt;
        this.ownerClosedBy = ownerClosedBy;
        this.priorProviderStatus = priorProviderStatus;
        this.transactionKey = transactionKey;
        this.openedAt = openedAt;
        this.handoffAt = handoffAt;
        this.promiseAt = promiseAt;
        this.nextCheckAt = nextCheckAt;
        this.checkAttempts = checkAttempts;
        this.refundRequestedAt = refundRequestedAt;
        this.concludedAt = concludedAt;
    }

    // 만료가 provider_status를 EXPIRED로 덮기 전에 불러야 만료 직전 상태가 남는다.
    public static RefundCase openOnExpiry(PaymentRecord payment, Instant openedAt, Duration firstCheckDelay) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            MoneyStatus.CHECKING, null, Owner.AUTO, null, null, null, null, payment.getProviderStatus(),
            payment.getTransactionKey(), openedAt, openedAt.plus(HANDOFF_AFTER), openedAt.plus(PROMISE_AFTER),
            openedAt.plus(firstCheckDelay), 0, null, null);
    }

    // 이 기능 이전에 만료되어 대상 기록이 없는 주문이다. 만료 직전 상태는 이미 덮였으므로 비우고, 기한은 알게 된 시각부터 센다.
    public static RefundCase openOnLateSuccess(PaymentRecord payment, String transactionKey, Instant now) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            MoneyStatus.CHECKING, null, Owner.AUTO, null, null, null, null, null, transactionKey, now, now.plus(HANDOFF_AFTER),
            now.plus(PROMISE_AFTER), now, 0, null, null);
    }

    // PG가 FAILED라 했다가 SUCCESS가 된 결과 뒤집힘은 자동 판단의 근거가 깨진 것이라 자동 환불 없이 사람이 맡는다(Q2).
    // 돈은 나갔으므로 청구 확인으로 두고, 끝나지 않았으므로 조회는 계속한다.
    public static RefundCase reverseOnLateSuccess(PaymentRecord payment, String transactionKey, Instant now,
        Duration personCheckDelay) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            MoneyStatus.CHARGED, null, Owner.PERSON, RESULT_REVERSED, now, null, null, payment.getProviderStatus(),
            transactionKey, now, now, now.plus(PROMISE_AFTER), now.plus(personCheckDelay), 0, null, null);
    }

    public static RefundCase restore(long orderId, String providerId, String providerOrderId, Money amount,
        MoneyStatus moneyStatus, RefundActor refundActor, Owner owner, String ownerReason, Instant ownerChangedAt,
        Instant ownerClosedAt, String ownerClosedBy, PaymentRecord.ProviderStatus priorProviderStatus, String transactionKey,
        Instant openedAt, Instant handoffAt, Instant promiseAt, Instant nextCheckAt, int checkAttempts,
        Instant refundRequestedAt, Instant concludedAt) {
        return new RefundCase(orderId, providerId, providerOrderId, amount, moneyStatus, refundActor, owner, ownerReason,
            ownerChangedAt, ownerClosedAt, ownerClosedBy, priorProviderStatus, transactionKey, openedAt, handoffAt, promiseAt,
            nextCheckAt, checkAttempts, refundRequestedAt, concludedAt);
    }

    public static RefundCase restore(long orderId, String providerId, String providerOrderId, Money amount,
        MoneyStatus moneyStatus, RefundActor refundActor, Owner owner, String ownerReason, Instant ownerChangedAt,
        Instant ownerClosedAt, String ownerClosedBy, PaymentRecord.ProviderStatus priorProviderStatus, String transactionKey,
        Instant openedAt, Instant handoffAt, Instant promiseAt, Instant nextCheckAt, int checkAttempts,
        Instant refundRequestedAt, Instant concludedAt, Instant lastCheckAt, CheckOutcome lastCheckOutcome,
        String lastCheckCause) {
        RefundCase refundCase = restore(orderId, providerId, providerOrderId, amount, moneyStatus, refundActor, owner,
            ownerReason, ownerChangedAt, ownerClosedAt, ownerClosedBy, priorProviderStatus, transactionKey, openedAt, handoffAt,
            promiseAt, nextCheckAt, checkAttempts, refundRequestedAt, concludedAt);
        refundCase.lastCheckAt = lastCheckAt;
        refundCase.lastCheckOutcome = lastCheckOutcome;
        refundCase.lastCheckCause = lastCheckCause;
        return refundCase;
    }

    // 환불은 스케줄러 한 곳에서만 보낸다. 여기서는 키를 남기고 확인 중이면 조회를 당길 뿐 돈의 상태를 앞으로 옮기지 않는다.
    // 청구 없음으로 끝난 뒤의 성공은 사실이 뒤집힌 것이라 청구 확인으로 다시 열고 사람이 맡는다. 환불됨은 끝난 사실이라 그대로 둔다.
    public void joinLateSuccess(String reportedTransactionKey, Instant now, Duration personCheckDelay) {
        switch (moneyStatus) {
            case CHECKING -> {
                storeKey(reportedTransactionKey);
                nextCheckAt = now;
            }
            case CHARGED, REFUND_REQUESTED, REFUND_UNKNOWN -> storeKey(reportedTransactionKey);
            case NO_CHARGE -> {
                storeKey(reportedTransactionKey);
                moneyStatus = MoneyStatus.CHARGED;
                concludedAt = null;
                owner = Owner.PERSON;
                ownerReason = RESULT_REVERSED;
                ownerChangedAt = now;
                ownerClosedAt = null;
                ownerClosedBy = null;
                nextCheckAt = now.plus(personCheckDelay);
            }
            case REFUNDED -> {
            }
        }
    }

    public boolean isConcluded() {
        return moneyStatus == MoneyStatus.REFUNDED || moneyStatus == MoneyStatus.NO_CHARGE;
    }

    public boolean isPersonOpen() {
        return owner == Owner.PERSON && ownerClosedAt == null;
    }

    public boolean isHandoffDue(Instant now) {
        return !isConcluded() && owner == Owner.AUTO && !now.isBefore(handoffAt);
    }

    // 자동 환불(취소 호출)을 보낼 수 있는지 판단하는 유일한 곳이다. 사람이 맡은 건에는 시스템이 끼어들지 않는다(to-pl 17:15).
    // 환불 요청함을 commit한 뒤에만 취소를 보내므로 청구 확인에서만 한 번 요청할 수 있다.
    public boolean canRequestRefund() {
        return moneyStatus == MoneyStatus.CHARGED && owner == Owner.AUTO;
    }

    public boolean canRequestRefund(CheckTrigger trigger) {
        return canRequestRefund();
    }

    public void requestRefund(CheckTrigger trigger, Instant now, Duration resultCheckDelay) {
        requestRefund(now, resultCheckDelay);
    }

    public void recordCheck(CheckOutcome outcome, String cause, Instant now) {
    }

    public void recordCharged(String foundTransactionKey) {
        requireMoney(MoneyStatus.CHECKING);
        storeKey(foundTransactionKey);
        moneyStatus = MoneyStatus.CHARGED;
    }

    // 결과가 기록되지 않은 채 멈춘 경우를 다음 회차가 조회로 확인하도록 결과 확인 시각을 잡아 둔다.
    public void requestRefund(Instant now, Duration resultCheckDelay) {
        if (!canRequestRefund()) {
            throw new IllegalStateException("자동 담당의 청구 확인 대상만 환불을 요청할 수 있습니다: " + moneyStatus + "/" + owner);
        }
        moneyStatus = MoneyStatus.REFUND_REQUESTED;
        refundRequestedAt = now;
        nextCheckAt = now.plus(resultCheckDelay);
    }

    // 환불 주체는 우리가 본 경로로 정한다: 우리가 요청한 뒤면 SYSTEM, 요청한 적 없는데 취소가 보이면 EXTERNAL(계획 2-1).
    public void markRefunded(Instant now) {
        requireOpen();
        refundActor = moneyStatus == MoneyStatus.REFUND_REQUESTED || moneyStatus == MoneyStatus.REFUND_UNKNOWN
            ? RefundActor.SYSTEM : RefundActor.EXTERNAL;
        conclude(MoneyStatus.REFUNDED, now);
    }

    public void markRefundUnknown(Instant now, Duration resultCheckDelay) {
        requireMoney(MoneyStatus.REFUND_REQUESTED);
        moneyStatus = MoneyStatus.REFUND_UNKNOWN;
        nextCheckAt = now.plus(resultCheckDelay);
    }

    // 거절은 효과가 없었으므로 돈은 청구 확인으로 돌아간다. 이미 사람이 맡았으면(요청 중 기한 이관) 처음 사유를 그대로 둔다.
    public void markRefundRejected(Instant now, Duration personCheckDelay) {
        requireMoney(MoneyStatus.REFUND_REQUESTED);
        moneyStatus = MoneyStatus.CHARGED;
        if (owner == Owner.AUTO) {
            changeOwnerToPerson(REFUND_REJECTED, now);
        }
        nextCheckAt = now.plus(personCheckDelay);
    }

    // 사람이 PG로 확인되지 않는 결론을 적는다(계획 5절, PL P7). 사람 담당 중이고 돈이 끝나지 않았을 때만 받는다.
    // 청구 확인의 청구 없음은 받는다. 사람 담당이 된 청구 확인은 대부분 불일치·복수·뒤집힘이라 PG가 말한 청구가 우리 주문 것이
    // 아닐 수 있고 최종 판단은 사람이 한다(담당 사유는 남아 맥락을 보존한다). 환불 요청함·결과 모름은 우리가 취소를 보냈을 수 있는 돈이라
    // 청구 없음이 아니다.
    public void resolveByPerson(PersonResult result, String closedBy, Instant now) {
        if (isConcluded()) {
            throw new RuleViolationException("돈의 상태가 이미 끝난 환불 확인 대상입니다: " + moneyStatus);
        }
        if (!isPersonOpen()) {
            throw new RuleViolationException("사람 담당 중인 환불 확인 대상이 아닙니다.");
        }
        if (result == PersonResult.NO_CHARGE
            && (moneyStatus == MoneyStatus.REFUND_REQUESTED || moneyStatus == MoneyStatus.REFUND_UNKNOWN)) {
            throw new RuleViolationException("환불을 요청한 뒤인 환불 확인 대상은 청구 없음으로 기록할 수 없습니다: " + moneyStatus);
        }
        if (result == PersonResult.REFUNDED) {
            refundActor = RefundActor.PERSON;
            moneyStatus = MoneyStatus.REFUNDED;
        } else {
            moneyStatus = MoneyStatus.NO_CHARGE;
        }
        concludedAt = now;
        nextCheckAt = null;
        ownerClosedAt = now;
        ownerClosedBy = closedBy;
    }

    public void closeNoCharge(Instant now) {
        requireMoney(MoneyStatus.CHECKING);
        conclude(MoneyStatus.NO_CHARGE, now);
    }

    // 담당만 바꾼다. 돈의 사실은 그대로 남고 돈이 끝나지 않았으므로 조회는 계속한다.
    public void assignPerson(String reason, Instant now, Duration personCheckDelay) {
        requireOpen();
        if (owner != Owner.AUTO) {
            throw new IllegalStateException("이미 사람이 맡은 대상입니다: " + ownerReason);
        }
        changeOwnerToPerson(reason, now);
        nextCheckAt = now.plus(personCheckDelay);
    }

    public void recordInconclusiveCheck(Instant now, Duration delay) {
        requireOpen();
        checkAttempts++;
        nextCheckAt = now.plus(delay);
    }

    // 회로 때문에 PG를 부르지 못한 것은 조회가 아니므로 횟수를 늘리지 않는다.
    public void postponeCheck(Instant now, Duration delay) {
        requireOpen();
        nextCheckAt = now.plus(delay);
    }

    public Duration inconclusiveCheckCeiling() {
        return owner == Owner.PERSON ? PERSON_CHECK_DELAY : backoffCeiling(checkAttempts + 1);
    }

    public Duration postponeCheckCeiling() {
        return owner == Owner.PERSON ? PERSON_CHECK_DELAY : backoffCeiling(checkAttempts);
    }

    private static Duration backoffCeiling(int attempts) {
        Duration delay = FIRST_CHECK_DELAY;
        for (int doubled = 1; doubled < attempts && delay.compareTo(MAX_CHECK_DELAY) < 0; doubled++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(MAX_CHECK_DELAY) > 0 ? MAX_CHECK_DELAY : delay;
    }

    private void changeOwnerToPerson(String reason, Instant now) {
        owner = Owner.PERSON;
        ownerReason = reason;
        ownerChangedAt = now;
    }

    // PG 사실로 돈이 끝나면 사람 담당도 같은 때에 닫는다. 사람이 따로 "처리 완료"를 누를 필요가 없다(to-pl 17:15).
    private void conclude(MoneyStatus concluded, Instant now) {
        moneyStatus = concluded;
        concludedAt = now;
        nextCheckAt = null;
        if (isPersonOpen()) {
            ownerClosedAt = now;
            ownerClosedBy = CLOSED_BY_SYSTEM;
        }
    }

    private void requireMoney(MoneyStatus expected) {
        if (moneyStatus != expected) {
            throw new IllegalStateException("환불 확인 대상의 돈의 상태가 " + expected + "가 아닙니다: " + moneyStatus);
        }
    }

    private void requireOpen() {
        if (isConcluded()) {
            throw new IllegalStateException("돈의 상태가 끝난 환불 확인 대상은 바꿀 수 없습니다: " + moneyStatus);
        }
    }

    private void storeKey(String reportedTransactionKey) {
        if (transactionKey == null && reportedTransactionKey != null) {
            transactionKey = reportedTransactionKey;
        }
    }

    public long getOrderId() {
        return orderId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public Money getAmount() {
        return amount;
    }

    public MoneyStatus getMoneyStatus() {
        return moneyStatus;
    }

    public RefundActor getRefundActor() {
        return refundActor;
    }

    public Owner getOwner() {
        return owner;
    }

    public String getOwnerReason() {
        return ownerReason;
    }

    public Instant getOwnerChangedAt() {
        return ownerChangedAt;
    }

    public Instant getOwnerClosedAt() {
        return ownerClosedAt;
    }

    public String getOwnerClosedBy() {
        return ownerClosedBy;
    }

    public PaymentRecord.ProviderStatus getPriorProviderStatus() {
        return priorProviderStatus;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getHandoffAt() {
        return handoffAt;
    }

    public Instant getPromiseAt() {
        return promiseAt;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public int getCheckAttempts() {
        return checkAttempts;
    }

    public Instant getRefundRequestedAt() {
        return refundRequestedAt;
    }

    public Instant getConcludedAt() {
        return concludedAt;
    }

    public Instant getLastCheckAt() {
        return lastCheckAt;
    }

    public CheckOutcome getLastCheckOutcome() {
        return lastCheckOutcome;
    }

    public String getLastCheckCause() {
        return lastCheckCause;
    }
}
