package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Entity
@Table(
    name = "payment_attempt",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempt_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempt_key", columnNames = "attempt_key"),
        @UniqueConstraint(name = "uk_payment_attempt_provider_order", columnNames = "provider_order_id")
    }
)
public class PaymentAttempt extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;
    @Column(name = "attempt_key", nullable = false)
    private String attemptKey;
    @Column(name = "provider_order_id", nullable = false)
    private String providerOrderId;
    @Column(nullable = false)
    private long amount;
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;
    @Column(name = "transaction_key")
    private String transactionKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentAttemptStatus status;

    protected PaymentAttempt() {}

    private PaymentAttempt(Long orderId, String attemptKey, long amount, String cardType, String cardNo) {
        if (orderId == null || orderId <= 0 || attemptKey == null || attemptKey.isBlank() || amount <= 0) {
            throw new IllegalArgumentException("결제 시도 정보가 올바르지 않습니다.");
        }
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.providerOrderId = String.format(Locale.ROOT, "LP-ORD-%010d", orderId);
        this.amount = amount;
        this.requestFingerprint = fingerprint(cardType, cardNo);
        this.status = PaymentAttemptStatus.REQUESTED;
    }

    public static PaymentAttempt start(Long orderId, String attemptKey, long amount, String cardType, String cardNo) {
        return new PaymentAttempt(orderId, attemptKey, amount, cardType, cardNo);
    }

    public Long getOrderId() { return orderId; }
    public String getAttemptKey() { return attemptKey; }
    public String getProviderOrderId() { return providerOrderId; }
    public long getAmount() { return amount; }
    public String getTransactionKey() { return transactionKey; }
    public PaymentAttemptStatus getStatus() { return status; }

    public boolean matchesRequest(long amount, String cardType, String cardNo) {
        return this.amount == amount && requestFingerprint.equals(fingerprint(cardType, cardNo));
    }

    private String fingerprint(String cardType, String cardNo) {
        if (cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new IllegalArgumentException("카드 정보가 필요합니다.");
        }
        // 길이를 붙여 필드 경계가 다른 입력을 구별하고, 시도 키를 포함해 시도별 비교값으로 만든다.
        String content = attemptKey.length() + ":" + attemptKey + cardType.length() + ":" + cardType + cardNo;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    public Result markPending(String transactionKey) {
        if (isOtherTransaction(transactionKey)) {
            return Result.CONFLICT;
        }
        if (status != PaymentAttemptStatus.REQUESTED && status != PaymentAttemptStatus.UNKNOWN) {
            return Result.IGNORED;
        }
        this.transactionKey = transactionKey;
        this.status = PaymentAttemptStatus.PENDING;
        return Result.APPLIED;
    }

    public Result markUnknown() {
        if (status != PaymentAttemptStatus.REQUESTED) {
            return Result.IGNORED;
        }
        this.status = PaymentAttemptStatus.UNKNOWN;
        return Result.APPLIED;
    }

    /** 접수 전 거절. 거래가 없으므로 거래 키 없이 FAILED로 끝낸다. 이미 진행이 알려진 시도와는 충돌이다. */
    public Result reject() {
        if (status == PaymentAttemptStatus.FAILED && transactionKey == null) {
            return Result.IGNORED;
        }
        if (status != PaymentAttemptStatus.REQUESTED) {
            return Result.CONFLICT;
        }
        this.status = PaymentAttemptStatus.FAILED;
        return Result.APPLIED;
    }

    /** 생성 요청이 PG에 닿지 않았다. 거래가 생길 수 없으므로 접수 전 거절과 같은 규칙으로 키 없이 닫는다. */
    public Result markNotSent() {
        return reject();
    }

    public Result succeed(String transactionKey) {
        return complete(transactionKey, PaymentAttemptStatus.SUCCESS);
    }

    public Result fail(String transactionKey) {
        return complete(transactionKey, PaymentAttemptStatus.FAILED);
    }

    private Result complete(String transactionKey, PaymentAttemptStatus result) {
        if (isOtherTransaction(transactionKey)) {
            return Result.CONFLICT;
        }
        if (status == result) {
            return Result.IGNORED;
        }
        if (status.isTerminal()) {
            return Result.CONFLICT;
        }
        this.transactionKey = transactionKey;
        this.status = result;
        return Result.APPLIED;
    }

    private boolean isOtherTransaction(String transactionKey) {
        if (transactionKey == null || transactionKey.isBlank()) {
            throw new IllegalArgumentException("PG 거래 키가 필요합니다.");
        }
        return this.transactionKey != null && !this.transactionKey.equals(transactionKey);
    }

    public enum Result { APPLIED, IGNORED, CONFLICT }
}
