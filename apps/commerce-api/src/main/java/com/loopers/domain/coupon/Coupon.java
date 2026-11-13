package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 사용자에게 발급된 쿠폰 한 장. 혜택은 발급 당시 캠페인 값을 복사해 둔다.
 *
 * <p>캠페인·사용자당 한 장은 유일 제약이 최종으로 지킨다.
 */
@Getter
@Entity
// 본인 보유 목록(user_id 동등, issued_at·id 내림차순)을 인덱스 순서로 읽는다.
@Table(
    name = "coupons",
    uniqueConstraints = @UniqueConstraint(name = "uk_coupons_campaign_user", columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_coupons_user_issued_id", columnList = "user_id, issued_at DESC, id DESC")
)
public class Coupon extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "discount_amount", nullable = false))
    private Money discountAmount;

    @Column(name = "issued_at", nullable = false)
    private ZonedDateTime issuedAt;

    protected Coupon() {}

    public static Coupon issue(CouponCampaign campaign, Long userId, ZonedDateTime issuedAt) {
        Coupon coupon = new Coupon();
        coupon.campaignId = campaign.getId();
        coupon.userId = userId;
        coupon.discountAmount = campaign.getDiscountAmount();
        coupon.issuedAt = issuedAt;
        return coupon;
    }
}
