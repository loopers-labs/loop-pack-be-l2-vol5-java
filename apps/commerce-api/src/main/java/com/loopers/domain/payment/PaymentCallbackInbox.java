package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

/**
 * PG가 보낸 callback 한 통. 받자마자 원문 그대로 저장하고 처리는 나중에 한다(docs/week5/design.md D-020).
 * 본문의 상태로 판단하지 않는다 — 키는 결제 시도를 찾는 데만 쓰고, 결과는 PG 조회로 확인한다.
 *
 * 처리 상태: 받음 → 완료 / 매칭 실패 / 재시도 → 포기(D-021). 조회 실패는 다음 회차에 다시 꺼내고(D-042),
 * 세 번째 실패에서 포기한다. 포기해도 결제는 일반 미종결 결제로서 계속 확인되므로 잃는 결제가 없다.
 */
// idx(status, next_try_at): 회차마다 "처리할 차례인 행"을 찾는 경로다.
@Entity
@Table(
    name = "payment_callback_inbox",
    indexes = @Index(name = "idx_payment_callback_inbox_status_next_try_at", columnList = "status, next_try_at")
)
public class PaymentCallbackInbox extends BaseEntity {

    public static final int MAX_TRIES = 3;

    @Column(columnDefinition = "TEXT")
    private String rawPayload;

    private String transactionKey;
    private String providerOrderId;
    private ZonedDateTime receivedAt;
    private Long attemptId;

    @Enumerated(EnumType.STRING)
    private CallbackInboxStatus status;

    private ZonedDateTime processedAt;
    private ZonedDateTime nextTryAt;
    private int tryCount;
    private String lastError;

    protected PaymentCallbackInbox() {}

    /** 본문이 깨져 키를 꺼내지 못했어도 원문은 저장한다 — 키가 없으면 워커가 매칭 실패로 정리한다. */
    public PaymentCallbackInbox(String rawPayload, String transactionKey, String providerOrderId, ZonedDateTime receivedAt) {
        this.rawPayload = rawPayload;
        this.transactionKey = transactionKey;
        this.providerOrderId = providerOrderId;
        this.receivedAt = receivedAt;
        this.status = CallbackInboxStatus.RECEIVED;
        this.nextTryAt = receivedAt;
    }

    public void unmatched(ZonedDateTime now) {
        this.status = CallbackInboxStatus.UNMATCHED;
        this.processedAt = now;
    }

    public void done(Long attemptId, ZonedDateTime now) {
        this.attemptId = attemptId;
        this.status = CallbackInboxStatus.DONE;
        this.processedAt = now;
    }

    /**
     * PG 조회에 실패했다. 세 번째 실패면 포기하고, 아니면 nextTryAt에 다시 꺼낸다.
     */
    public void failed(Long attemptId, String error, ZonedDateTime now, ZonedDateTime nextTryAt) {
        this.attemptId = attemptId;
        this.tryCount++;
        this.lastError = error;
        if (tryCount >= MAX_TRIES) {
            this.status = CallbackInboxStatus.GAVE_UP;
            this.processedAt = now;
            return;
        }
        this.status = CallbackInboxStatus.RETRY;
        this.nextTryAt = nextTryAt;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public ZonedDateTime getReceivedAt() {
        return receivedAt;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public CallbackInboxStatus getStatus() {
        return status;
    }

    public ZonedDateTime getProcessedAt() {
        return processedAt;
    }

    public ZonedDateTime getNextTryAt() {
        return nextTryAt;
    }

    public int getTryCount() {
        return tryCount;
    }

    public String getLastError() {
        return lastError;
    }
}
