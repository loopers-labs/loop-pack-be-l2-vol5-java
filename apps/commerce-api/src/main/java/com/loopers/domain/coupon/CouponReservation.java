package com.loopers.domain.coupon;

import com.loopers.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

@Entity
@Table(name = "coupon_reservation",
    uniqueConstraints = @UniqueConstraint(name = "uq_coupon_reservation_campaign_user", columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_coupon_reservation_waiting", columnList = "campaign_id, status, id"))
public class CouponReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false, updatable = false)
    private CouponCampaign campaign;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CouponStatus status;

    protected CouponReservation() {
    }

    public CouponReservation(CouponCampaign campaign, User user) {
        this.campaign = Objects.requireNonNull(campaign);
        this.user = Objects.requireNonNull(user);
        this.status = CouponStatus.WAITING;
    }

    public void markIssued() {
        if (status != CouponStatus.WAITING) {
            throw new IllegalStateException("Only a waiting reservation can be issued");
        }
        status = CouponStatus.ISSUED;
    }

    public Long getId() {
        return id;
    }

    public CouponCampaign getCampaign() {
        return campaign;
    }

    public User getUser() {
        return user;
    }

    public CouponStatus getStatus() {
        return status;
    }
}
