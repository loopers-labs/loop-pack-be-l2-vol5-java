package com.loopers.domains.payment.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;

// 기록은 upsert 한 문장(PaymentAnomalyJpaRepository.upsert)이라 이 엔티티의 메모리 값은 같은 트랜잭션 안에서 낡을 수 있다.
// 해소가 바꾼 칸만 쓰게 해 낡은 occurrences·last_summary로 upsert 결과를 덮지 않는다.
@Entity
@DynamicUpdate
@Table(name = "payment_anomalies", uniqueConstraints =
    @UniqueConstraint(name = "uk_payment_anomalies_order_kind", columnNames = {"order_id", "kind"}))
public class PaymentAnomalyJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false, length = 50)
    private String kind;
    @Column(nullable = false, updatable = false)
    private Instant firstSeenAt;
    @Column(nullable = false)
    private Instant lastSeenAt;
    @Column(nullable = false)
    private long occurrences;
    @Column(nullable = false, length = 500)
    private String lastSummary;
    private Instant resolvedAt;

    protected PaymentAnomalyJpaEntity() {
    }

    void resolve(Instant now) {
        resolvedAt = now;
    }
}
