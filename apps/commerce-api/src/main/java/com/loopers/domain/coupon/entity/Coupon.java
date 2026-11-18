package com.loopers.domain.coupon.entity;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 발급된 결과만 담는다. 혜택은 발급 때의 캠페인 값을 복사해 둔다 (8주차 D-150, D-152).
 * 캠페인은 id 로만 참조한다. 외래키 검사의 공유 잠금이 캠페인 행을 다시 핫 스팟으로 만들기 때문이다 (8주차 D-156)
 */
@Getter
@Entity
@Table(
    name = "coupon",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_coupon_campaign_user", columnNames = {"campaign_id", "user_id"}),
        @UniqueConstraint(name = "uk_coupon_campaign_slot", columnNames = {"campaign_id", "slot_no"})
    },
    indexes = @Index(name = "idx_coupon_user_issued", columnList = "user_id, issued_at, id")
)
public class Coupon extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "slot_no", nullable = false)
    private int slotNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(nullable = false, length = 100)
    private String description;

    @Column(name = "issued_at", nullable = false)
    private ZonedDateTime issuedAt;

    protected Coupon() {}

    private Coupon(CouponCampaign campaign, Long userId, int slotNo, ZonedDateTime issuedAt) {
        this.campaignId = campaign.getId();
        this.userId = userId;
        this.slotNo = slotNo;
        this.discountType = campaign.getDiscountType();
        this.discountValue = campaign.getDiscountValue();
        this.description = campaign.getDescription();
        this.issuedAt = issuedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** 범위 밖 슬롯은 슬롯 배정 버그라 업무 판정이 아니라 미분류 예외로 둔다 (8주차 D-126, D-141) */
    public static Coupon issue(CouponCampaign campaign, Long userId, int slotNo, ZonedDateTime issuedAt) {
        if (!campaign.isOpen()) {
            throw new CoreException(CouponErrorCode.CAMPAIGN_NOT_ACTIVE);
        }
        if (!campaign.hasSlot(slotNo)) {
            throw new IllegalStateException("한도 밖 슬롯. campaignId=" + campaign.getId() + " slotNo=" + slotNo);
        }
        return new Coupon(campaign, userId, slotNo, issuedAt);
    }
}
