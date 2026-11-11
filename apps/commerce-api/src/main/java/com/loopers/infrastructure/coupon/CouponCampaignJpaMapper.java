package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;

final class CouponCampaignJpaMapper {

    private CouponCampaignJpaMapper() {}

    static CouponCampaign toDomain(CouponCampaignJpaEntity entity) {
        return CouponCampaign.reconstitute(
            entity.getId(), entity.getName(), entity.getTotalQuota(), entity.getIssuedCount(),
            entity.getBenefitDescription(), entity.getStartAt(), entity.getEndAt(), entity.getTerminatedAt()
        );
    }

    static CouponCampaignJpaEntity toNewEntity(CouponCampaign campaign) {
        return CouponCampaignJpaEntity.create(
            campaign.getName(), campaign.getTotalQuota(), campaign.getBenefitDescription(),
            campaign.getStartAt(), campaign.getEndAt()
        );
    }
}
