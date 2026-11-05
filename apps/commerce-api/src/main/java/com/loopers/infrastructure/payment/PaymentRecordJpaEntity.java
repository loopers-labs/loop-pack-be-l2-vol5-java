package com.loopers.infrastructure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// 주문당 결제 기록은 하나다. 이관 스크립트의 재실행과 confirm 경쟁을 DB 유일 제약으로도 막는다(설계 22절).
// 시도 키는 같은 키를 다른 주문에 쓰는 경쟁을 막도록 유일하다. 포인트 기록은 시도 키가 없다(NULL은 중복 허용).
// 만료 대상 조회(결과 PENDING·시작 시각 경과)는 (result, started_at) 인덱스로 찾는다(설계 26절).
@Entity
@Table(name = "payment_records", uniqueConstraints = {
    @UniqueConstraint(name = "uk_payment_records_order_id", columnNames = "order_id"),
    @UniqueConstraint(name = "uk_payment_records_attempt_key", columnNames = "attempt_key")
}, indexes = @Index(name = "idx_payment_records_result_started_at", columnList = "result, started_at"))
public class PaymentRecordJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false, length = 20)
    private String method;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Column(nullable = false, length = 20)
    private String result;
    @Column(updatable = false, length = 64)
    private String attemptKey;
    @Column(length = 50)
    private String providerId;
    @Column(updatable = false, length = 50)
    private String providerOrderId;
    @Column(length = 100)
    private String transactionKey;
    @Column(length = 30)
    private String providerStatus;
    @Column(updatable = false)
    private Instant startedAt;

    protected PaymentRecordJpaEntity() { }

    PaymentRecordJpaEntity(long orderId, String method, long amount, String attemptKey, String providerId,
        String providerOrderId, Instant startedAt) {
        this.orderId = orderId;
        this.method = method;
        this.amount = amount;
        this.attemptKey = attemptKey;
        this.providerId = providerId;
        this.providerOrderId = providerOrderId;
        this.startedAt = startedAt;
    }

    void update(String result, String transactionKey, String providerStatus, String providerId) {
        this.providerId = providerId;
        this.result = result;
        this.transactionKey = transactionKey;
        this.providerStatus = providerStatus;
    }

    Long getId() { return id; }
    long getOrderId() { return orderId; }
    String getMethod() { return method; }
    long getAmount() { return amount; }
    String getResult() { return result; }
    String getAttemptKey() { return attemptKey; }
    String getProviderId() { return providerId; }
    String getProviderOrderId() { return providerOrderId; }
    String getTransactionKey() { return transactionKey; }
    String getProviderStatus() { return providerStatus; }
    Instant getStartedAt() { return startedAt; }
}
