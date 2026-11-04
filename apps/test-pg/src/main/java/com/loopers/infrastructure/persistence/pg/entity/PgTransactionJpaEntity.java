package com.loopers.infrastructure.persistence.pg.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pg_transactions", indexes = {
        @Index(name = "idx_pg_order", columnList = "provider_order_id"),
        @Index(name = "idx_pg_due", columnList = "status,due_at")
})
public class PgTransactionJpaEntity {
    @Id
    @Column(name = "transaction_key", length = 64)
    String transactionKey;
    @Column(name = "provider_order_id", nullable = false, length = 100)
    String orderId;
    @Column(nullable = false)
    long amount;
    @Column(name = "callback_url", nullable = false, length = 2048)
    String callbackUrl;
    @Column(nullable = false, length = 16)
    String status;
    @Embedded
    PgScenarioFields scenario;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "due_at")
    Instant dueAt;

    protected PgTransactionJpaEntity() {}
}
