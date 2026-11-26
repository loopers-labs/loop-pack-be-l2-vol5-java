package com.loopers.domains.payment.domain;

import com.loopers.common.domain.Money;
import java.time.Duration;
import java.time.Instant;

// 만료된 PG 결제 하나를 환불 결론까지 추적하는 기록이다(10주차 5단계 계획 2-1). 주문당 결제 기록이 하나라 주문 ID로 식별한다.
public final class RefundCase {
    public enum Status { TRACKING, REFUND_REQUESTED, REFUNDED, REFUND_UNKNOWN, CLOSED_NO_CHARGE, HANDED_TO_HUMAN }

    // [임시] 자동 추적 2일 뒤 사람에게 넘기고, 고객에게는 4일 안 환불을 약속한다(사용자 결정 D188).
    public static final Duration HANDOFF_AFTER = Duration.ofDays(2);
    public static final Duration PROMISE_AFTER = Duration.ofDays(4);
    public static final String RESULT_REVERSED = "RESULT_REVERSED";
    public static final String HANDOFF_DEADLINE = "HANDOFF_DEADLINE";
    public static final String TRANSACTION_MISMATCH = "TRANSACTION_MISMATCH";
    public static final String MULTIPLE_TRANSACTIONS = "MULTIPLE_TRANSACTIONS";
    public static final String REFUND_REJECTED = "REFUND_REJECTED";
    // [임시] 결론 없는 조회 뒤 다음 조회 간격은 1분에서 두 배씩 늘려 1시간에서 멈춘다(계획 2-2).
    public static final Duration FIRST_CHECK_DELAY = Duration.ofMinutes(1);
    public static final Duration MAX_CHECK_DELAY = Duration.ofHours(1);

    private final long orderId;
    private final String providerId;
    private final String providerOrderId;
    private final Money amount;
    private Status status;
    private final PaymentRecord.ProviderStatus priorProviderStatus;
    private String transactionKey;
    private final Instant openedAt;
    private final Instant handoffAt;
    private final Instant promiseAt;
    private Instant nextCheckAt;
    private int checkAttempts;
    private Instant refundRequestedAt;
    private Instant concludedAt;
    private String reason;

    private RefundCase(long orderId, String providerId, String providerOrderId, Money amount, Status status,
        PaymentRecord.ProviderStatus priorProviderStatus, String transactionKey, Instant openedAt, Instant handoffAt,
        Instant promiseAt, Instant nextCheckAt, int checkAttempts, Instant refundRequestedAt, Instant concludedAt,
        String reason) {
        this.orderId = orderId;
        this.providerId = providerId;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.status = status;
        this.priorProviderStatus = priorProviderStatus;
        this.transactionKey = transactionKey;
        this.openedAt = openedAt;
        this.handoffAt = handoffAt;
        this.promiseAt = promiseAt;
        this.nextCheckAt = nextCheckAt;
        this.checkAttempts = checkAttempts;
        this.refundRequestedAt = refundRequestedAt;
        this.concludedAt = concludedAt;
        this.reason = reason;
    }

    // 만료가 provider_status를 EXPIRED로 덮기 전에 불러야 만료 직전 상태가 남는다. 첫 조회는 만료 직후로 둔다.
    public static RefundCase openOnExpiry(PaymentRecord payment, Instant openedAt) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            Status.TRACKING, payment.getProviderStatus(), payment.getTransactionKey(), openedAt, openedAt.plus(HANDOFF_AFTER),
            openedAt.plus(PROMISE_AFTER), openedAt, 0, null, null, null);
    }

    // 이 기능 이전에 만료되어 대상 기록이 없는 주문이다. 만료 직전 상태는 이미 덮였으므로 비우고, 기한은 알게 된 시각부터 센다.
    public static RefundCase openOnLateSuccess(PaymentRecord payment, String transactionKey, Instant now) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            Status.TRACKING, null, transactionKey, now, now.plus(HANDOFF_AFTER), now.plus(PROMISE_AFTER), now, 0, null, null,
            null);
    }

    // PG가 FAILED라 했다가 SUCCESS가 된 결과 뒤집힘은 자동 판단의 근거가 깨진 것이라 자동 환불하지 않고 바로 사람에게 넘긴다(Q2).
    public static RefundCase handToHumanOnReversal(PaymentRecord payment, String transactionKey, Instant now) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            Status.HANDED_TO_HUMAN, payment.getProviderStatus(), transactionKey, now, now, now.plus(PROMISE_AFTER), null, 0, null,
            now, RESULT_REVERSED);
    }

    public static RefundCase restore(long orderId, String providerId, String providerOrderId, Money amount, Status status,
        PaymentRecord.ProviderStatus priorProviderStatus, String transactionKey, Instant openedAt, Instant handoffAt,
        Instant promiseAt, Instant nextCheckAt, int checkAttempts, Instant refundRequestedAt, Instant concludedAt,
        String reason) {
        return new RefundCase(orderId, providerId, providerOrderId, amount, status, priorProviderStatus, transactionKey, openedAt,
            handoffAt, promiseAt, nextCheckAt, checkAttempts, refundRequestedAt, concludedAt, reason);
    }

    // 환불은 스케줄러 한 곳에서만 보낸다. 여기서는 키를 남기고 조회를 당길 뿐 환불 요청 상태를 건드리지 않는다.
    // 돈 안 나감으로 닫은 뒤의 성공은 결과 뒤집힘이라 사람에게 넘긴다. 환불 완료·사람 이관은 끝난 결론이라 그대로 둔다.
    public void joinLateSuccess(String reportedTransactionKey, Instant now) {
        switch (status) {
            case TRACKING -> {
                storeKey(reportedTransactionKey);
                nextCheckAt = now;
            }
            case REFUND_REQUESTED, REFUND_UNKNOWN -> storeKey(reportedTransactionKey);
            case CLOSED_NO_CHARGE -> {
                storeKey(reportedTransactionKey);
                status = Status.HANDED_TO_HUMAN;
                reason = RESULT_REVERSED;
                concludedAt = now;
                nextCheckAt = null;
            }
            case REFUNDED, HANDED_TO_HUMAN -> {
            }
        }
    }

    public boolean isConcluded() {
        return false;
    }

    public boolean isHandoffDue(Instant now) {
        return false;
    }

    public void requestRefund(String foundTransactionKey, Instant now) {
    }

    public void markRefunded(Instant now) {
    }

    public void markRefundUnknown(Instant now) {
    }

    public void closeNoCharge(Instant now) {
    }

    public void handOff(String handoffReason, Instant now) {
    }

    public void recordInconclusiveCheck(Instant now) {
    }

    public void postponeCheck(Instant now) {
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

    public Status getStatus() {
        return status;
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

    public String getReason() {
        return reason;
    }
}
