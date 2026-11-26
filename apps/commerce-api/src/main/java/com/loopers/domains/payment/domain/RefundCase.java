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

    // 골격: w10s5 Green에서 만료 직전 상태·키 복사와 기한 계산을 채운다.
    public static RefundCase openOnExpiry(PaymentRecord payment, Instant openedAt) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            null, null, null, null, null, null, null, 0, null, null, null);
    }

    // 골격: 이 기능 이전에 만료되어 대상 기록이 없는 주문의 늦은 성공.
    public static RefundCase openOnLateSuccess(PaymentRecord payment, String transactionKey, Instant now) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            null, null, null, null, null, null, null, 0, null, null, null);
    }

    // 골격: PG가 FAILED라 했던 주문의 늦은 성공. 자동 환불하지 않고 사람에게 바로 넘긴다(Q2).
    public static RefundCase handToHumanOnReversal(PaymentRecord payment, String transactionKey, Instant now) {
        return new RefundCase(payment.getOrderId(), payment.getProviderId(), payment.getProviderOrderId(), payment.getAmount(),
            null, null, null, null, null, null, null, 0, null, null, null);
    }

    public static RefundCase restore(long orderId, String providerId, String providerOrderId, Money amount, Status status,
        PaymentRecord.ProviderStatus priorProviderStatus, String transactionKey, Instant openedAt, Instant handoffAt,
        Instant promiseAt, Instant nextCheckAt, int checkAttempts, Instant refundRequestedAt, Instant concludedAt,
        String reason) {
        return new RefundCase(orderId, providerId, providerOrderId, amount, status, priorProviderStatus, transactionKey, openedAt,
            handoffAt, promiseAt, nextCheckAt, checkAttempts, refundRequestedAt, concludedAt, reason);
    }

    // 골격: 만료 주문의 늦은 성공 키를 저장하고 다음 조회를 지금으로 당긴다.
    public void joinLateSuccess(String reportedTransactionKey, Instant now) {
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
