package com.loopers.infrastructure.persistence.coupon.entity;

import com.loopers.domain.coupon.model.CouponBenefit;
import com.loopers.domain.coupon.model.CouponCampaign;
import org.springframework.stereotype.Component;

@Component
public class CouponCampaignEntityMapper {
    public CouponCampaign toDomain(CouponCampaignJpaEntity entity) {
        return CouponCampaign.restore(entity.getId(), entity.getName(), entity.isActive(), entity.getTotalQuota(),
            entity.getIssuedCount(), new CouponBenefit(entity.getBenefitDescription(), entity.getDiscountAmount()),
            entity.getStartsAt(), entity.getEndsAt());
    }

    public CouponCampaignJpaEntity toNewEntity(CouponCampaign campaign) {
        return new CouponCampaignJpaEntity(campaign.getName(), campaign.getTotalQuota(),
            campaign.getBenefit().description(), campaign.getBenefit().discountAmount(),
            campaign.getStartsAt(), campaign.getEndsAt(), campaign.isActive(), campaign.getIssuedCount());
    }

    public void apply(CouponCampaign campaign, CouponCampaignJpaEntity entity) {
        entity.apply(campaign.isActive(), campaign.getIssuedCount());
    }
}
