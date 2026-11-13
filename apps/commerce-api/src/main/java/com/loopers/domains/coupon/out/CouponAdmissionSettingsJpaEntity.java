package com.loopers.domains.coupon.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "coupon_admission_settings")
public class CouponAdmissionSettingsJpaEntity {
    static final long SINGLE_ROW_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private boolean open;

    @Column(nullable = false)
    @ColumnDefault("1")
    private long epoch;

    @Column(length = 500)
    private String closedReason;

    private Instant closedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CouponAdmissionSettingsJpaEntity() {
    }

    int capacity() {
        return capacity;
    }

    boolean open() {
        return open;
    }

    long epoch() {
        return epoch;
    }

    String closedReason() {
        return closedReason;
    }

    Instant closedAt() {
        return closedAt;
    }
}
