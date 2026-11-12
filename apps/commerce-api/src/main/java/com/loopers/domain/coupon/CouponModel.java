package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * 사용자에게 지급된 쿠폰 한 장. 캠페인·사용자는 물리 FK 없는 scalar 참조이며, 동시 발급의 중복은
 * {@code uk_coupon_campaign_user} 가 최종 방어한다. 혜택은 발급 당시 값을 복사하고 캠페인 이름은 복사하지 않는다.
 */
@Entity
@Table(
    name = "coupon",
    uniqueConstraints = @UniqueConstraint(name = CouponModel.UNIQUE_CAMPAIGN_USER, columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_coupon_user_created", columnList = "user_id, created_at, id")
)
@Getter
public class CouponModel extends BaseEntity {

    /** 사용자당 캠페인 쿠폰 1장의 최종 방어 제약 이름. 저장 예외 분류에서 같은 이름으로 식별한다. */
    public static final String UNIQUE_CAMPAIGN_USER = "uk_coupon_campaign_user";

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Convert(converter = CouponCampaignModel.BenefitTypeConverter.class)
    @Column(name = "benefit_type", nullable = false, updatable = false, length = 16)
    private CouponBenefitType benefitType;

    @Column(name = "benefit_value", nullable = false, updatable = false)
    private long benefitValue;

    protected CouponModel() {}

    private CouponModel(Long userId, Long campaignId, CouponBenefitType benefitType, long benefitValue) {
        this.userId = userId;
        this.campaignId = campaignId;
        this.benefitType = benefitType;
        this.benefitValue = benefitValue;
    }

    /** 값은 서버가 읽은 캠페인 데이터이므로 위반은 INTERNAL_ERROR 다. */
    public static CouponModel issue(Long userId, Long campaignId, CouponBenefitType benefitType, long benefitValue) {
        CouponModel coupon = new CouponModel(userId, campaignId, benefitType, benefitValue);
        coupon.guard();
        return coupon;
    }

    @Override
    protected void guard() {
        if (userId == null || userId <= 0L) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰 사용자 ID 는 양수여야 합니다.");
        }
        if (campaignId == null || campaignId <= 0L) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰 캠페인 ID 는 양수여야 합니다.");
        }
        CouponBenefits.require(benefitType, benefitValue);
    }
}
