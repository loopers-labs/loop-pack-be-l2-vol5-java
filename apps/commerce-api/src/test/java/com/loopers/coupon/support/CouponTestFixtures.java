package com.loopers.coupon.support;

import com.loopers.coupon.adapter.out.persistence.CouponCampaignJpaRepository;
import com.loopers.coupon.adapter.out.persistence.CouponJpaRepository;
import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.domain.CouponDraft;
import com.loopers.coupon.domain.CouponScope;
import com.loopers.coupon.domain.DiscountType;
import com.loopers.coupon.domain.ValidityType;

import java.time.ZonedDateTime;
import java.util.Set;

/**
 * 지금 열려 있는 캠페인(1시간 전 시작, 1시간 뒤 마감)과 쿠폰 한 장.
 */
public final class CouponTestFixtures {

    private CouponTestFixtures() {}

    public static CouponCampaign openCampaign(CouponCampaignJpaRepository campaigns, CouponJpaRepository coupons,
                                              int totalQuantity, Integer queueCapacity) {
        ZonedDateTime now = ZonedDateTime.now();
        CouponCampaign campaign = campaigns.save(new CouponCampaign("선착순", totalQuantity, queueCapacity, now.minusHours(1), now.plusHours(1)));
        coupons.save(Coupon.of(campaign.getId(), new CouponDraft("3천원", DiscountType.FIXED, 3_000, null, 0,
            CouponScope.ALL, Set.of(), ValidityType.DAYS_FROM_ISSUE, null, null, 7, false)));
        return campaign;
    }
}
