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

// 스케줄러는 결론 전 상태에서 다음 조회 시각이 지난 대상을 찾는다. 그 조회를 (status, next_check_at) 인덱스로 받는다.
@Entity
@Table(name = "payment_refund_cases",
    indexes = @Index(name = "idx_payment_refund_cases_status_next_check", columnList = "status, next_check_at"))
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
    private String status;
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
    @Column(length = 500)
    private String reason;

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
        status = refundCase.getStatus().name();
        transactionKey = refundCase.getTransactionKey();
        nextCheckAt = refundCase.getNextCheckAt();
        checkAttempts = refundCase.getCheckAttempts();
        refundRequestedAt = refundCase.getRefundRequestedAt();
        concludedAt = refundCase.getConcludedAt();
        reason = refundCase.getReason();
    }

    RefundCase toRefundCase() {
        return RefundCase.restore(orderId, providerId, providerOrderId, new Money(amount), RefundCase.Status.valueOf(status),
            priorProviderStatus == null ? null : PaymentRecord.ProviderStatus.valueOf(priorProviderStatus), transactionKey,
            openedAt, handoffAt, promiseAt, nextCheckAt, checkAttempts, refundRequestedAt, concludedAt, reason);
    }
}
