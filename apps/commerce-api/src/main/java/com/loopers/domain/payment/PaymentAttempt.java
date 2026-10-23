package com.loopers.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 시도의 발송 이력과 외부 관찰을 저장하는 모델이다.
 * 트랜잭션 조율·HTTP 호출을 수행하지 않으며, 여러 실행 사이의 동시성은 저장 경계에서 보호해야 한다.
 * 금액의 비음수 검사는 저장 값의 표현 범위이며 상품 가격·주문 생성의 업무 검증을 대체하지 않는다.
 */
@Entity
@Table(name = "payment_attempt", uniqueConstraints = {
    @UniqueConstraint(name = "uk_payment_attempt_order", columnNames = "order_id"),
    @UniqueConstraint(name = "uk_payment_attempt_key", columnNames = "attempt_key"),
    @UniqueConstraint(name = "uk_payment_attempt_provider_order", columnNames = {"provider_account", "provider_order_id"}),
    @UniqueConstraint(name = "uk_payment_attempt_transaction", columnNames = {"provider_account", "transaction_key"})
})
public class PaymentAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "attempt_key", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID attemptKey;

    @Column(name = "provider_account", nullable = false, updatable = false,
        columnDefinition = "varchar(100) collate utf8mb4_0900_bin")
    private String providerAccount;

    @Column(name = "provider_order_id", nullable = false, updatable = false,
        columnDefinition = "varchar(255) collate utf8mb4_0900_bin")
    private String providerOrderId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "request_fingerprint", updatable = false, length = 32, columnDefinition = "varbinary(32)")
    private byte[] requestFingerprint;

    @Column(name = "fingerprint_version", updatable = false)
    private Integer fingerprintVersion;

    @Column(name = "key_version", updatable = false)
    private Integer keyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentAttemptStatus status = PaymentAttemptStatus.NOT_OBSERVED;

    @Column(name = "send_started_at")
    private ZonedDateTime sendStartedAt;

    @Column(name = "transaction_key", columnDefinition = "varchar(255) collate utf8mb4_0900_bin")
    private String transactionKey;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(long orderId, UUID attemptKey, String providerAccount, String providerOrderId, long amount) {
        if (orderId <= 0 || attemptKey == null || providerAccount == null || providerAccount.isBlank()
            || providerOrderId == null || providerOrderId.isBlank() || amount < 0) {
            throw new IllegalArgumentException("Payment attempt requires identities and a non-negative amount");
        }
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.providerAccount = providerAccount;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
    }

    public PaymentAttempt(long orderId, UUID attemptKey, String providerAccount, String providerOrderId,
                          long amount, PaymentRequestFingerprint fingerprint) {
        this(orderId, attemptKey, providerAccount, providerOrderId, amount);
        if (fingerprint == null) {
            throw new IllegalArgumentException("Payment request fingerprint is required");
        }
        this.requestFingerprint = fingerprint.digest();
        this.fingerprintVersion = fingerprint.fingerprintVersion();
        this.keyVersion = fingerprint.keyVersion();
    }

    /** 과거 시도의 전체 NULL은 비교 자료 없음이며, 이후 입력으로 보충하지 않는다. */
    public Optional<PaymentRequestFingerprint> getRequestFingerprint() {
        if (requestFingerprint == null && fingerprintVersion == null && keyVersion == null) {
            return Optional.empty();
        }
        if (requestFingerprint == null || requestFingerprint.length != 32 || fingerprintVersion == null
            || fingerprintVersion <= 0 || keyVersion == null || keyVersion <= 0) {
            throw new PaymentFingerprintUnavailableException(
                PaymentFingerprintUnavailableException.Reason.CORRUPT_STORED_FINGERPRINT,
                "Payment request fingerprint is unavailable");
        }
        return Optional.of(new PaymentRequestFingerprint(fingerprintVersion, keyVersion, requestFingerprint));
    }

    public PaymentRequestFingerprint requireRequestFingerprint() {
        return getRequestFingerprint().orElseThrow(() ->
            new PaymentFingerprintUnavailableException(
                PaymentFingerprintUnavailableException.Reason.LEGACY_FINGERPRINT_MISSING,
                "Payment request fingerprint is unavailable"));
    }

    /**
     * 이 객체의 최초 발송 시작만 기록한다. PG 수신이나 DB 커밋의 증거는 아니다.
     * 호출자는 TX A에서 예약과 함께 원자적 전이·커밋을 확인한 실행만 실제 PG 호출을 하도록 연결해야 한다.
     */
    public boolean startDispatch(ZonedDateTime timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (sendStartedAt != null) {
            return false;
        }
        sendStartedAt = timestamp.truncatedTo(ChronoUnit.MICROS);
        return true;
    }

    /**
     * PG 생성 응답 또는 권위 있는 조회에서 얻은 거래를 반영한다. raw callback을 직접 전달하지 않는다.
     * 주문·예약의 효과와 함께 저장하는 책임은 이후 TX B에 있다.
     */
    public PaymentObservationResult observe(PaymentTransaction transaction) {
        requireDispatchStarted();
        Objects.requireNonNull(transaction, "transaction");
        if (!matchesTarget(transaction.providerAccount(), transaction.providerOrderId(), transaction.amount())) {
            return PaymentObservationResult.CONFLICT;
        }
        if (transactionKey != null && !transactionKey.equals(transaction.transactionKey())) {
            return PaymentObservationResult.CONFLICT;
        }
        PaymentAttemptStatus observed = switch (transaction.status()) {
            case PENDING -> PaymentAttemptStatus.PENDING;
            case SUCCESS -> PaymentAttemptStatus.SUCCESS;
            case FAILED -> PaymentAttemptStatus.FAILED;
        };
        if (isTerminal()) {
            if (transactionKey == null) {
                return PaymentObservationResult.CONFLICT;
            }
            return observed == status || observed == PaymentAttemptStatus.PENDING
                ? PaymentObservationResult.UNCHANGED : PaymentObservationResult.CONFLICT;
        }
        if (status == observed) {
            return PaymentObservationResult.UNCHANGED;
        }
        transactionKey = transaction.transactionKey();
        status = observed;
        return PaymentObservationResult.APPLIED;
    }

    public PaymentObservationResult markUnknown() {
        requireDispatchStarted();
        if (isTerminal() || status == PaymentAttemptStatus.UNKNOWN) {
            return PaymentObservationResult.UNCHANGED;
        }
        status = PaymentAttemptStatus.UNKNOWN;
        return PaymentObservationResult.APPLIED;
    }

    /**
     * adapter가 과제의 strict 422·오류 코드·transactionCreated=false를 모두 확인한 효과 0건 거절 전용이다.
     * timeout·일반 4xx·조회 결과 0건만으로 호출하면 안 된다. HTTP 증거 검증은 이 모델 밖의 책임이다.
     */
    public PaymentObservationResult observeRejectedBeforeCreate(String account, String providerOrder, long amount) {
        requireDispatchStarted();
        if (!matchesTarget(account, providerOrder, amount) || transactionKey != null) {
            return PaymentObservationResult.CONFLICT;
        }
        if (status == PaymentAttemptStatus.FAILED) {
            return PaymentObservationResult.UNCHANGED;
        }
        status = PaymentAttemptStatus.FAILED;
        return PaymentObservationResult.APPLIED;
    }

    private boolean matchesTarget(String account, String providerOrder, long observedAmount) {
        return providerAccount.equals(account) && providerOrderId.equals(providerOrder) && amount == observedAmount;
    }

    private boolean isTerminal() {
        return status == PaymentAttemptStatus.SUCCESS || status == PaymentAttemptStatus.FAILED;
    }

    private void requireDispatchStarted() {
        if (sendStartedAt == null) {
            throw new IllegalStateException("Payment dispatch has not started");
        }
    }

    public Long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public UUID getAttemptKey() {
        return attemptKey;
    }

    public String getProviderAccount() {
        return providerAccount;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public ZonedDateTime getSendStartedAt() {
        return sendStartedAt;
    }

    public String getTransactionKey() {
        return transactionKey;
    }
}
