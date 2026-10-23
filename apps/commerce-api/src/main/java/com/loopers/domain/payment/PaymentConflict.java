package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 기록된 결제 상태와 PG 관찰 결과가 어긋난 사실. PG 관찰 결과는 우리가 만든 사실이 아니라서 관찰한 순간 적어 두지 않으면
 * 사라진다 — 그래서 계산하지 않고 저장한다(docs/week5/design.md D-011). 한 시도에 여러 번 생길 수 있어 별도 테이블이다.
 *
 * 같은 내용이 다시 관찰되면 새 행 대신 lastDetectedAt만 갱신한다(D-012). "같은 내용"은 사유·기록된 결제 상태·
 * PG 관찰 결과가 모두 같은 것이다. 거래가 2건 이상이면 관찰 결과 필드는 비어 있다.
 */
@Entity
@Table(name = "payment_conflict", indexes = @Index(name = "idx_payment_conflict_attempt_id", columnList = "attempt_id"))
public class PaymentConflict extends BaseEntity {

    private Long attemptId;

    @Enumerated(EnumType.STRING)
    private PaymentConflictReason reason;

    @Enumerated(EnumType.STRING)
    private PaymentAttemptStatus recordedStatus;

    @Enumerated(EnumType.STRING)
    private PaymentObservation.Status observedStatus;

    private String observedTransactionKey;
    private Long observedAmount;

    private ZonedDateTime firstDetectedAt;
    private ZonedDateTime lastDetectedAt;
    private ZonedDateTime resolvedAt;

    protected PaymentConflict() {}

    public PaymentConflict(Long attemptId, PaymentConflictReason reason, PaymentAttemptStatus recordedStatus,
                           PaymentObservation observation) {
        if (attemptId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충돌의 결제 시도 ID는 비어있을 수 없습니다.");
        }
        if (reason == null || recordedStatus == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충돌 사유와 기록된 결제 상태는 비어있을 수 없습니다.");
        }
        this.attemptId = attemptId;
        this.reason = reason;
        this.recordedStatus = recordedStatus;
        if (observation != null) {
            this.observedStatus = observation.status();
            this.observedTransactionKey = observation.transactionKey();
            this.observedAmount = observation.amount();
        }
        ZonedDateTime now = ZonedDateTime.now();
        this.firstDetectedAt = now;
        this.lastDetectedAt = now;
    }

    /**
     * 새로 발견한 충돌이 이 행과 같은 내용인지. 처리 여부와 상관없이 내용만 비교한다 —
     * 처리 전이면 갱신, 처리 후면 무시하는 판단은 호출자가 한다(D-012).
     */
    public boolean hasSameContentAs(PaymentConflictReason reason, PaymentAttemptStatus recordedStatus,
                                    PaymentObservation observation) {
        return this.reason == reason
            && this.recordedStatus == recordedStatus
            && this.observedStatus == (observation == null ? null : observation.status())
            && Objects.equals(this.observedTransactionKey, observation == null ? null : observation.transactionKey())
            && Objects.equals(this.observedAmount, observation == null ? null : observation.amount());
    }

    public void detectedAgain() {
        this.lastDetectedAt = ZonedDateTime.now();
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public PaymentConflictReason getReason() {
        return reason;
    }

    public PaymentAttemptStatus getRecordedStatus() {
        return recordedStatus;
    }

    public PaymentObservation.Status getObservedStatus() {
        return observedStatus;
    }

    public String getObservedTransactionKey() {
        return observedTransactionKey;
    }

    public Long getObservedAmount() {
        return observedAmount;
    }

    public ZonedDateTime getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public ZonedDateTime getLastDetectedAt() {
        return lastDetectedAt;
    }

    public ZonedDateTime getResolvedAt() {
        return resolvedAt;
    }
}
