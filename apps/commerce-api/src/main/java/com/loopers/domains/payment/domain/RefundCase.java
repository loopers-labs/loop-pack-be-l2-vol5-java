package com.loopers.domains.payment.domain;

import com.loopers.common.domain.Money;
import java.time.Duration;
import java.time.Instant;

// 만료된 PG 결제 하나를 환불 결론까지 추적하는 기록이다(10주차 5b 계획 2절). 주문당 결제 기록이 하나라 주문 ID로 식별한다.
// 한 칸 한 뜻: 돈의 상태(money_status)는 PG·사람이 확인한 돈의 사실만, 담당(owner)은 다음 일을 누가 하는지만 담는다.
public final class RefundCase {
    public enum MoneyStatus { CHECKING, CHARGED, REFUND_REQUESTED, REFUND_UNKNOWN, REFUNDED, NO_CHARGE }

    public enum Owner { AUTO, PERSON }

    public enum RefundActor { SYSTEM, PERSON, EXTERNAL }

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

    // 골격(Red): 업무 규칙은 Green에서 채운다.
    public static RefundCase openOnExpiry(PaymentRecord payment, Instant openedAt, Duration firstCheckDelay) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            MoneyStatus.CHECKING, null, Owner.AUTO, null, null, null, null, payment.getProviderStatus(),
            payment.getTransactionKey(), openedAt, openedAt.plus(HANDOFF_AFTER), openedAt.plus(PROMISE_AFTER), openedAt, 0, null,
            null);
    }

    public static RefundCase openOnLateSuccess(PaymentRecord payment, String transactionKey, Instant now) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            MoneyStatus.CHECKING, null, Owner.AUTO, null, null, null, null, null, transactionKey, now, now.plus(HANDOFF_AFTER),
            now.plus(PROMISE_AFTER), now, 0, null, null);
    }

    public static RefundCase reverseOnLateSuccess(PaymentRecord payment, String transactionKey, Instant now,
        Duration personCheckDelay) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            MoneyStatus.CHECKING, null, Owner.AUTO, null, null, null, null, payment.getProviderStatus(), transactionKey, now, now,
            now.plus(PROMISE_AFTER), now, 0, null, null);
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

    public void joinLateSuccess(String reportedTransactionKey, Instant now, Duration personCheckDelay) {
    }

    public boolean isConcluded() {
        return false;
    }

    public boolean isPersonOpen() {
        return false;
    }

    public boolean isHandoffDue(Instant now) {
        return false;
    }

    public boolean canRequestRefund() {
        return false;
    }

    public void recordCharged(String foundTransactionKey) {
    }

    public void requestRefund(Instant now, Duration resultCheckDelay) {
    }

    public void markRefunded(Instant now) {
    }

    public void markRefundUnknown(Instant now, Duration resultCheckDelay) {
    }

    public void markRefundRejected(Instant now, Duration personCheckDelay) {
    }

    public void closeNoCharge(Instant now) {
    }

    public void assignPerson(String reason, Instant now, Duration personCheckDelay) {
    }

    public void recordInconclusiveCheck(Instant now, Duration delay) {
    }

    public void postponeCheck(Instant now, Duration delay) {
    }

    public Duration inconclusiveCheckCeiling() {
        return Duration.ZERO;
    }

    public Duration postponeCheckCeiling() {
        return Duration.ZERO;
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
}
