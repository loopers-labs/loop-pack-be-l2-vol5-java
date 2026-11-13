package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 선착순 쿠폰 캠페인 (8주차 설계 §6).
 * - totalQuota·issuedCount 가 총량 정합성(INV-W8-02)의 근거다. issuedCount 증가는 리포지토리의 조건부 UPDATE 만 한다
 * - 발급되는 쿠폰의 혜택(benefitType·benefitValue)과 만료 시각(couponExpiresAt)은 캠페인이 정의하고, 발급 시 쿠폰에 복사된다
 */
@Getter
@Entity
@Table(name = "coupon_campaign")
public class CouponCampaign extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int totalQuota;

    @Column(nullable = false)
    private int issuedCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Coupon.BenefitType benefitType;

    @Column(nullable = false)
    private long benefitValue;

    @Column(nullable = false)
    private ZonedDateTime couponExpiresAt;

    protected CouponCampaign() {}

    public CouponCampaign(
        String name,
        int totalQuota,
        Coupon.BenefitType benefitType,
        long benefitValue,
        ZonedDateTime couponExpiresAt
    ) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 이름은 필수입니다.");
        }
        if (totalQuota <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "총 발급량은 1 이상이어야 합니다.");
        }
        if (benefitType == null || couponExpiresAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 혜택 종류와 만료 시각은 필수입니다.");
        }
        if (benefitValue <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 혜택 값은 1 이상이어야 합니다.");
        }
        this.name = name;
        this.active = true;
        this.totalQuota = totalQuota;
        this.issuedCount = 0;
        this.benefitType = benefitType;
        this.benefitValue = benefitValue;
        this.couponExpiresAt = couponExpiresAt;
    }

    public void close() {
        this.active = false;
    }
}
