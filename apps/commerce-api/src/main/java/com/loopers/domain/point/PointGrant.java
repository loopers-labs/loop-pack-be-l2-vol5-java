package com.loopers.domain.point;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "point_grant")
public class PointGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointGrantType type;

    @Column(nullable = false)
    private ZonedDateTime grantedAt;

    private ZonedDateTime expiresAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "point_grant_id", nullable = false)
    private List<PointUsage> usages = new ArrayList<>();

    protected PointGrant() {}

    PointGrant(long amount, PointGrantType type, ZonedDateTime grantedAt, ZonedDateTime expiresAt) {
        this.amount = amount;
        this.type = type;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }

    long remainingAmount() {
        return amount - usages.stream().mapToLong(PointUsage::getAmount).sum();
    }

    boolean expiresAt(ZonedDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    ZonedDateTime getExpiresAt() {
        return expiresAt;
    }

    ZonedDateTime getGrantedAt() {
        return grantedAt;
    }

    Long getId() {
        return id;
    }

    void record(long amount, PointUsageType type, ZonedDateTime occurredAt) {
        if (amount <= 0 || amount > remainingAmount()) {
            throw new IllegalArgumentException("포인트 사용 금액이 올바르지 않습니다.");
        }
        usages.add(new PointUsage(amount, type, occurredAt));
    }
}
