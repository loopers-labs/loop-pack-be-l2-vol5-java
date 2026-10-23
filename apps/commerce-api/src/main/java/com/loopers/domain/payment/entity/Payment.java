package com.loopers.domain.payment.entity;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;
import java.util.List;
import com.loopers.domain.payment.enums.PaymentConflict;
import com.loopers.domain.payment.enums.PaymentErrorCode;
import com.loopers.domain.payment.enums.PaymentStatus;
import com.loopers.domain.payment.enums.ReservationChange;
import com.loopers.domain.payment.enums.ReservationStatus;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;

/**
 * 주문 하나의 결제 시도. 상태는 5주차 설계 2.2 전이표대로만 바뀌며 create 재발송 전이는 없음 (D-60).
 */
@Entity
@Table(name = "payment", uniqueConstraints = {
    @UniqueConstraint(name = "uk_payment_order", columnNames = "order_id"),
    @UniqueConstraint(name = "uk_payment_attempt_key", columnNames = "attempt_key"),
    @UniqueConstraint(name = "uk_payment_provider_order", columnNames = "provider_order_id")
})
public class Payment extends BaseEntity {

    public static final int MAX_ATTEMPT_KEY_LENGTH = 64;
    private static final String PROVIDER_ORDER_ID_FORMAT = "LP-ORD-%010d";

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "attempt_key", nullable = false, length = MAX_ATTEMPT_KEY_LENGTH)
    private String attemptKey;

    @Column(name = "provider_order_id", nullable = false)
    private String providerOrderId;

    /** REQUESTED · UNKNOWN · REJECTED · NOT_REQUIRED 에는 없음 */
    @Column(name = "transaction_key")
    private String transactionKey;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status", nullable = false)
    private ReservationStatus reservationStatus;

    /** 결제 상태와 따로 남기는 마지막 충돌 기록 (D-65) */
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_reason")
    private PaymentConflict conflictReason;

    @Column(name = "conflicted_at")
    private ZonedDateTime conflictedAt;

    protected Payment() {}

    private Payment(Long orderId, String attemptKey, long amount) {
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.providerOrderId = providerOrderIdOf(orderId);
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
        this.reservationStatus = ReservationStatus.RESERVED;
    }

    /** 재고는 같은 TX 에서 이미 확보한 상태 */
    public static Payment request(Long orderId, String attemptKey, long amount) {
        validateAttemptKey(attemptKey);
        if (amount < 0) {
            throw new CoreException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
        return new Payment(orderId, attemptKey, amount);
    }

    /** 0원 주문의 시도. 시도 키 멱등성을 PG 주문과 같게 유지하려고 남김 (D-68) */
    public static Payment notRequired(Long orderId, String attemptKey) {
        validateAttemptKey(attemptKey);
        Payment payment = new Payment(orderId, attemptKey, 0L);
        payment.status = PaymentStatus.NOT_REQUIRED;
        payment.reservationStatus = ReservationStatus.CONSUMED;
        return payment;
    }

    /** 주문을 다루기 전에 부를 수 있도록 따로 둠 */
    public static void validateAttemptKey(String attemptKey) {
        if (attemptKey == null || attemptKey.isBlank() || attemptKey.length() > MAX_ATTEMPT_KEY_LENGTH) {
            throw new CoreException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }

    /** 주문 42 → LP-ORD-0000000042. PG 요청과 조회에 같은 값을 씀 */
    public static String providerOrderIdOf(Long orderId) {
        return String.format(PROVIDER_ORDER_ID_FORMAT, orderId);
    }

    public boolean hasAttemptKey(String attemptKey) {
        return this.attemptKey.equals(attemptKey);
    }

    public ReservationChange apply(PaymentCreateResult result, ZonedDateTime now) {
        return switch (status) {
            case REQUESTED -> applyCreateToRequested(result);
            case PENDING -> {
                applyLateCreateToPending(result, now);
                yield ReservationChange.NONE;
            }
            // UNKNOWN 은 발송자가 결과 미확인을 받았을 때만 들어오므로 생성 응답이 다시 오지 않음
            case UNKNOWN, SUCCESS, FAILED, REJECTED, NOT_REQUIRED -> ReservationChange.NONE;
        };
    }

    public ReservationChange apply(PaymentLookupResult result, ZonedDateTime now) {
        if (result instanceof PaymentLookupResult.Found found) {
            return applyLookup(found.transactions(), now);
        }
        // 조회 불가: 마지막으로 아는 상태 유지
        return ReservationChange.NONE;
    }

    private ReservationChange applyCreateToRequested(PaymentCreateResult result) {
        if (result instanceof PaymentCreateResult.Accepted accepted) {
            this.transactionKey = accepted.transactionKey();
            this.status = PaymentStatus.PENDING;
            return ReservationChange.NONE;
        }
        if (result instanceof PaymentCreateResult.RejectedWithoutEffect) {
            return settle(PaymentStatus.REJECTED);
        }
        this.status = PaymentStatus.UNKNOWN;
        return ReservationChange.NONE;
    }

    private void applyLateCreateToPending(PaymentCreateResult result, ZonedDateTime now) {
        if (result instanceof PaymentCreateResult.Accepted accepted && !accepted.transactionKey().equals(transactionKey)) {
            recordConflict(PaymentConflict.LATE_CREATE_CONTRADICTION, now);
        }
        // 거래가 있는데 "효과 없음" 응답이 온 모순
        if (result instanceof PaymentCreateResult.RejectedWithoutEffect) {
            recordConflict(PaymentConflict.LATE_CREATE_CONTRADICTION, now);
        }
        // 늦은 결과 미확인은 UNKNOWN 으로 낮추지 않음
    }

    private ReservationChange applyLookup(List<ProviderTransaction> transactions, ZonedDateTime now) {
        LookupEvent event = classify(transactions);
        return switch (status) {
            case REQUESTED, UNKNOWN -> applyLookupToUnsettled(event, transactions, now);
            case PENDING -> applyLookupToPending(event, now);
            case SUCCESS -> applyLookupToTerminal(event == LookupEvent.FAILED || event.isConflict(), now);
            case FAILED -> applyLookupToTerminal(event == LookupEvent.SUCCESS || event.isConflict(), now);
            // 거래가 없어야 할 시도에서 거래가 발견되면 모순
            case REJECTED, NOT_REQUIRED -> applyLookupToTerminal(event != LookupEvent.NONE, now);
        };
    }

    private ReservationChange applyLookupToUnsettled(LookupEvent event, List<ProviderTransaction> transactions, ZonedDateTime now) {
        return switch (event) {
            case PENDING -> {
                this.transactionKey = transactions.get(0).transactionKey();
                this.status = PaymentStatus.PENDING;
                yield ReservationChange.NONE;
            }
            case SUCCESS -> {
                this.transactionKey = transactions.get(0).transactionKey();
                yield settle(PaymentStatus.SUCCESS);
            }
            case FAILED -> {
                this.transactionKey = transactions.get(0).transactionKey();
                yield settle(PaymentStatus.FAILED);
            }
            // 발송 중이거나 발송하지 못했을 수 있음. 재발송하지 않음
            case NONE -> ReservationChange.NONE;
            case MULTIPLE, MISMATCHED -> {
                recordConflict(event.conflict(), now);
                yield ReservationChange.NONE;
            }
        };
    }

    private ReservationChange applyLookupToPending(LookupEvent event, ZonedDateTime now) {
        return switch (event) {
            case PENDING -> ReservationChange.NONE;
            case SUCCESS -> settle(PaymentStatus.SUCCESS);
            case FAILED -> settle(PaymentStatus.FAILED);
            // 조회 반영 지연일 수 있어 상태를 굳히지 않고 기록만 함
            case NONE -> {
                recordConflict(PaymentConflict.MISSING_KNOWN_TRANSACTION, now);
                yield ReservationChange.NONE;
            }
            case MULTIPLE, MISMATCHED -> {
                recordConflict(event.conflict(), now);
                yield ReservationChange.NONE;
            }
        };
    }

    /** 종결 뒤에는 상태를 덮어쓰지 않고, 상충하는 결과만 기록함 */
    private ReservationChange applyLookupToTerminal(boolean contradicts, ZonedDateTime now) {
        if (contradicts) {
            recordConflict(PaymentConflict.TERMINAL_CONTRADICTION, now);
        }
        return ReservationChange.NONE;
    }

    /** 예약 상태가 결제 상태와 따로 바뀌지 않도록 한 곳에서만 전이함 (D-59) */
    private ReservationChange settle(PaymentStatus terminalStatus) {
        this.status = terminalStatus;
        if (terminalStatus == PaymentStatus.SUCCESS) {
            this.reservationStatus = ReservationStatus.CONSUMED;
            return ReservationChange.CONSUME;
        }
        this.reservationStatus = ReservationStatus.RELEASED;
        return ReservationChange.RELEASE;
    }

    private void recordConflict(PaymentConflict conflict, ZonedDateTime now) {
        this.conflictReason = conflict;
        this.conflictedAt = now;
    }

    /** 2건 이상이면 첫 거래를 고르지 않음 */
    private LookupEvent classify(List<ProviderTransaction> transactions) {
        if (transactions.isEmpty()) {
            return LookupEvent.NONE;
        }
        if (transactions.size() > 1) {
            return LookupEvent.MULTIPLE;
        }
        ProviderTransaction transaction = transactions.get(0);
        boolean matches = providerOrderId.equals(transaction.providerOrderId())
            && amount == transaction.amount()
            && transaction.transactionKey() != null
            && (transactionKey == null || transactionKey.equals(transaction.transactionKey()));
        if (!matches || transaction.status() == null) {
            return LookupEvent.MISMATCHED;
        }
        return switch (transaction.status()) {
            case PENDING -> LookupEvent.PENDING;
            case SUCCESS -> LookupEvent.SUCCESS;
            case FAILED -> LookupEvent.FAILED;
        };
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public ReservationStatus getReservationStatus() {
        return reservationStatus;
    }

    public PaymentConflict getConflictReason() {
        return conflictReason;
    }

    public ZonedDateTime getConflictedAt() {
        return conflictedAt;
    }
}
