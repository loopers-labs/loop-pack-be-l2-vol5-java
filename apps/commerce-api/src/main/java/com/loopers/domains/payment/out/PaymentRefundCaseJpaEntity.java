package com.loopers.domains.payment.out;

import com.loopers.common.domain.Money;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.domain.RefundCase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

// 스케줄러는 돈의 상태가 끝나지 않은 대상 중 다음 조회 시각이 지난 것을 찾는다(담당 무관). 그 조회를 (money_status, next_check_at) 인덱스로 받는다.
@Entity
@Table(name = "payment_refund_cases",
    indexes = @Index(name = "idx_payment_refund_cases_money_status_next_check", columnList = "money_status, next_check_at"))
public class PaymentRefundCaseJpaEntity {
    @Id
    private long orderId;
    @Column(nullable = false, updatable = false, length = 50)
    private String providerId;
    @Column(nullable = false, updatable = false, length = 50)
    private String providerOrderId;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Column(nullable = false, length = 30)
    private String moneyStatus;
    @Column(length = 20)
    private String refundActor;
    @Column(nullable = false, length = 20)
    private String owner;
    @Column(length = 500)
    private String ownerReason;
    private Instant ownerChangedAt;
    private Instant ownerClosedAt;
    @Column(length = 100)
    private String ownerClosedBy;
    @Column(updatable = false, length = 30)
    private String priorProviderStatus;
    @Column(length = 100)
    private String transactionKey;
    @Column(nullable = false, updatable = false)
    private Instant openedAt;
    @Column(nullable = false, updatable = false)
    private Instant handoffAt;
    @Column(nullable = false, updatable = false)
    private Instant promiseAt;
    private Instant nextCheckAt;
    @Column(nullable = false)
    private int checkAttempts;
    private Instant refundRequestedAt;
    private Instant concludedAt;
    private Instant lastCheckAt;
    @Column(length = 30)
    private String lastCheckOutcome;
    @Column(length = 100)
    private String lastCheckCause;

    protected PaymentRefundCaseJpaEntity() {
    }

    PaymentRefundCaseJpaEntity(RefundCase refundCase) {
        orderId = refundCase.getOrderId();
        providerId = refundCase.getProviderId();
        providerOrderId = refundCase.getProviderOrderId();
        amount = refundCase.getAmount().value();
        priorProviderStatus = refundCase.getPriorProviderStatus() == null ? null : refundCase.getPriorProviderStatus().name();
        openedAt = refundCase.getOpenedAt();
        handoffAt = refundCase.getHandoffAt();
        promiseAt = refundCase.getPromiseAt();
        update(refundCase);
    }

    void update(RefundCase refundCase) {
        moneyStatus = refundCase.getMoneyStatus().name();
        refundActor = refundCase.getRefundActor() == null ? null : refundCase.getRefundActor().name();
        owner = refundCase.getOwner().name();
        ownerReason = refundCase.getOwnerReason();
        ownerChangedAt = refundCase.getOwnerChangedAt();
        ownerClosedAt = refundCase.getOwnerClosedAt();
        ownerClosedBy = refundCase.getOwnerClosedBy();
        transactionKey = refundCase.getTransactionKey();
        nextCheckAt = refundCase.getNextCheckAt();
        checkAttempts = refundCase.getCheckAttempts();
        refundRequestedAt = refundCase.getRefundRequestedAt();
        concludedAt = refundCase.getConcludedAt();
        lastCheckAt = refundCase.getLastCheckAt();
        lastCheckOutcome = refundCase.getLastCheckOutcome() == null ? null : refundCase.getLastCheckOutcome().name();
        lastCheckCause = refundCase.getLastCheckCause();
    }

    RefundCase toRefundCase() {
        return RefundCase.restore(orderId, providerId, providerOrderId, new Money(amount),
            RefundCase.MoneyStatus.valueOf(moneyStatus), refundActor == null ? null : RefundCase.RefundActor.valueOf(refundActor),
            RefundCase.Owner.valueOf(owner), ownerReason, ownerChangedAt, ownerClosedAt, ownerClosedBy,
            priorProviderStatus == null ? null : PaymentRecord.ProviderStatus.valueOf(priorProviderStatus), transactionKey,
            openedAt, handoffAt, promiseAt, nextCheckAt, checkAttempts, refundRequestedAt, concludedAt, lastCheckAt,
            lastCheckOutcome == null ? null : RefundCase.CheckOutcome.valueOf(lastCheckOutcome), lastCheckCause);
    }
}
