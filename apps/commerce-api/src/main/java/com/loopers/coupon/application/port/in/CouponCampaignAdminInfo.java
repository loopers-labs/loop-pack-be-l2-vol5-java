package com.loopers.coupon.application.port.in;

import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.domain.CouponScope;
import com.loopers.coupon.domain.DiscountType;
import com.loopers.coupon.domain.ValidityType;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * 관리자 캠페인 결과: 당첨자 수·발급 수·줄 길이 제한·기간과 담긴 쿠폰들.
 */
public record CouponCampaignAdminInfo(
    Long id,
    String name,
    int totalQuantity,
    int issuedCount,
    int queueCapacity,
    ZonedDateTime issueStartAt,
    ZonedDateTime issueEndAt,
    List<CouponInfo> coupons
) {
    public static CouponCampaignAdminInfo of(CouponCampaign campaign, List<Coupon> coupons) {
        return new CouponCampaignAdminInfo(
            campaign.getId(),
            campaign.getName(),
            campaign.getTotalQuantity(),
            campaign.getIssuedCount(),
            campaign.getQueueCapacity(),
            campaign.getIssueStartAt(),
            campaign.getIssueEndAt(),
            coupons.stream().map(CouponInfo::from).toList()
        );
    }

    public record CouponInfo(
        Long id,
        String name,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        long minOrderAmount,
        CouponScope scope,
        Set<Long> applicableProductIds,
        ValidityType validityType,
        ZonedDateTime validFrom,
        ZonedDateTime validUntil,
        Integer validDays,
        boolean stackable
    ) {
        public static CouponInfo from(Coupon coupon) {
            return new CouponInfo(
                coupon.getId(),
                coupon.getName(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMaxDiscountAmount(),
                coupon.getMinOrderAmount(),
                coupon.getScope(),
                coupon.getApplicableProductIds(),
                coupon.getValidityType(),
                coupon.getValidFrom(),
                coupon.getValidUntil(),
                coupon.getValidDays(),
                coupon.isStackable()
            );
        }
    }
}
