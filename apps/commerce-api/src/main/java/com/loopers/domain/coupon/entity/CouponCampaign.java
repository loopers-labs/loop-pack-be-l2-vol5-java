package com.loopers.domain.coupon.entity;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.coupon.enums.CampaignStatus;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 정해진 쿠폰을 정해진 수량만 발급하는 행사.
 * 한도는 쿠폰의 슬롯 유일 제약이 지키고, issuedCount 는 발급 TX 밖에서 맞춘다 (8주차 D-141, D-142)
 */
@Getter
@Entity
@Table(name = "coupon_campaign")
public class CouponCampaign extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 50;
    private static final int MAX_DESCRIPTION_LENGTH = 100;
    private static final long MAX_PERCENTAGE = 100L;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignStatus status;

    @Column(name = "total_quota", nullable = false)
    private int totalQuota;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(nullable = false, length = MAX_DESCRIPTION_LENGTH)
    private String description;

    protected CouponCampaign() {}

    public CouponCampaign(String name, int totalQuota, DiscountType discountType, long discountValue, String description) {
        validateName(name);
        validateTotalQuota(totalQuota);
        validateDiscount(discountType, discountValue);
        validateDescription(description);
        this.name = name;
        this.status = CampaignStatus.READY;
        this.totalQuota = totalQuota;
        this.issuedCount = 0;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.description = description;
    }

    /** 열기 명령을 다시 실행해도 이어서 열리도록 이미 OPEN 이면 그대로 둔다 (8주차 D-166) */
    public void open() {
        if (status == CampaignStatus.OPEN) {
            return;
        }
        if (status != CampaignStatus.READY) {
            throw new IllegalStateException("READY 가 아닌 캠페인은 열 수 없음. campaignId=" + getId() + " status=" + status);
        }
        this.status = CampaignStatus.OPEN;
    }

    /** CLOSED 는 끝 상태다. 승격을 멈추고 남은 예비를 품절로 본다 (8주차 D-124) */
    public void close() {
        if (status == CampaignStatus.CLOSED) {
            return;
        }
        if (status != CampaignStatus.OPEN) {
            throw new IllegalStateException("OPEN 이 아닌 캠페인은 닫을 수 없음. campaignId=" + getId() + " status=" + status);
        }
        this.status = CampaignStatus.CLOSED;
    }

    public boolean isOpen() {
        return status == CampaignStatus.OPEN;
    }

    /** 한도 + 예비 인원. 예비 인원은 한도의 비율이고 최소 1명이다 (8주차 D-122) */
    public int reserveLimit(double reserveRatio) {
        if (reserveRatio < 0) {
            throw new IllegalArgumentException("예비 비율은 0 이상. reserveRatio=" + reserveRatio);
        }
        int reserve = Math.max(1, (int) Math.ceil(totalQuota * reserveRatio));
        return Math.addExact(totalQuota, reserve);
    }

    /** 슬롯은 1 ~ 한도뿐이라 한 슬롯에 한 장이면 발급 수가 한도를 넘지 못한다 (8주차 D-141) */
    public boolean hasSlot(int slotNo) {
        return slotNo >= 1 && slotNo <= totalQuota;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(CouponErrorCode.INVALID_CAMPAIGN_NAME);
        }
    }

    private static void validateTotalQuota(int totalQuota) {
        if (totalQuota < 1) {
            throw new CoreException(CouponErrorCode.INVALID_TOTAL_QUOTA);
        }
    }

    private static void validateDiscount(DiscountType discountType, long discountValue) {
        boolean valid = discountType != null && discountValue >= 1
            && (discountType != DiscountType.PERCENTAGE || discountValue <= MAX_PERCENTAGE);
        if (!valid) {
            throw new CoreException(CouponErrorCode.INVALID_DISCOUNT);
        }
    }

    private static void validateDescription(String description) {
        if (description == null || description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new CoreException(CouponErrorCode.INVALID_COUPON_DESCRIPTION);
        }
    }
}
