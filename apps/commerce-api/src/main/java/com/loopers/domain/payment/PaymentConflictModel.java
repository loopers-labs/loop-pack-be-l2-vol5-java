package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.EnumNameConverter;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 결제당 마지막 충돌 요약 0~1행(설계 3.6). 이력을 쌓지 않고 새 충돌마다 내용을 교체한다.
 * 정상 확인으로 요약을 지우지 않으며 생성·교체는 같은 Order 잠금 아래 결과 반영 TX 에서 한다.
 */
@Entity
@Table(
    name = "payment_conflict",
    uniqueConstraints = @UniqueConstraint(name = "uk_payment_conflict_payment", columnNames = "payment_id")
)
@Getter
public class PaymentConflictModel extends BaseEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Convert(converter = CodeConverter.class)
    @Column(name = "code", nullable = false, length = 48)
    private PaymentConflictCode code;

    @Column(name = "observed_order_id", columnDefinition = "text")
    private String observedOrderId;

    @Column(name = "observed_transaction_key", columnDefinition = "text")
    private String observedTransactionKey;

    @Column(name = "observed_amount")
    private Long observedAmount;

    @Convert(converter = ObservedStatusConverter.class)
    @Column(name = "observed_status", length = 16)
    private PgTransactionStatus observedStatus;

    @Column(name = "observed_count")
    private Integer observedCount;

    /** 마지막 요약의 저장 시각. 교체 순서 판정에 쓰지 않는다. */
    @Column(name = "detected_at", nullable = false)
    private ZonedDateTime detectedAt;

    protected PaymentConflictModel() {}

    public static class CodeConverter extends EnumNameConverter<PaymentConflictCode> {
        public CodeConverter() {
            super(PaymentConflictCode.class);
        }
    }

    public static class ObservedStatusConverter extends EnumNameConverter<PgTransactionStatus> {
        public ObservedStatusConverter() {
            super(PgTransactionStatus.class);
        }
    }

    private PaymentConflictModel(Long paymentId) {
        this.paymentId = paymentId;
    }

    public static PaymentConflictModel first(Long paymentId, PaymentConflictCode code, ConflictEvidence evidence,
                                             ZonedDateTime detectedAt) {
        if (paymentId == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "충돌 요약의 결제 ID 필수");
        }
        PaymentConflictModel conflict = new PaymentConflictModel(paymentId);
        conflict.record(code, evidence, detectedAt);
        return conflict;
    }

    /** 이 TX 에서 반영한 충돌로 요약 전체를 교체한다. 시작·수신 시각으로 교체를 막지 않는다. */
    public void record(PaymentConflictCode code, ConflictEvidence evidence, ZonedDateTime detectedAt) {
        if (code == null || evidence == null || detectedAt == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "충돌 코드·근거·시각 필수");
        }
        this.code = code;
        this.observedOrderId = evidence.observedOrderId();
        this.observedTransactionKey = evidence.observedTransactionKey();
        this.observedAmount = evidence.observedAmount();
        this.observedStatus = evidence.observedStatus();
        this.observedCount = evidence.observedCount();
        this.detectedAt = detectedAt;
    }
}
