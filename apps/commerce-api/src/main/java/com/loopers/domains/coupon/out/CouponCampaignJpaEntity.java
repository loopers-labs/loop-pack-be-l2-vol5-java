package com.loopers.domains.coupon.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "coupon_campaign")
@Check(name = "chk_coupon_campaign_issued", constraints = "issued_count >= 0 and issued_count <= total_quota")
public class CouponCampaignJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int totalQuota;

    @Column(nullable = false)
    private int issuedCount;

    @Column(nullable = false)
    private long discountAmount;

    @Column(nullable = false)
    private Instant createdAt;

    protected CouponCampaignJpaEntity() {
    }

    long id() {
        return id;
    }

    String name() {
        return name;
    }

    boolean active() {
        return active;
    }

    int totalQuota() {
        return totalQuota;
    }

    int issuedCount() {
        return issuedCount;
    }

    long discountAmount() {
        return discountAmount;
    }
}
