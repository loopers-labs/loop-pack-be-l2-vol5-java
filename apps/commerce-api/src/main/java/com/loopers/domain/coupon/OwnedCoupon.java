package com.loopers.domain.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Entity
@Table(name = "owned_coupon", uniqueConstraints = @UniqueConstraint(
    name = "uq_owned_coupon_reservation", columnNames = "reservation_id"))
public class OwnedCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, updatable = false)
    private CouponReservation reservation;

    @Column(name = "benefit_description", nullable = false, updatable = false, length = 255)
    private String benefitDescription;

    @Column(name = "issued_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant issuedAt;

    protected OwnedCoupon() {
    }

    public OwnedCoupon(CouponReservation reservation, String benefitDescription) {
        this.reservation = Objects.requireNonNull(reservation);
        this.benefitDescription = Objects.requireNonNull(benefitDescription);
    }

    @PrePersist
    private void markIssuedAt() {
        issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public CouponReservation getReservation() {
        return reservation;
    }

    public String getBenefitDescription() {
        return benefitDescription;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
