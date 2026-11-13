package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {

    private final CouponCampaignJpaRepository campaignJpaRepository;

    @Override
    public CouponCampaign save(CouponCampaign campaign) {
        return campaignJpaRepository.save(CouponCampaignEntity.from(campaign)).toDomain();
    }

    @Override
    public Optional<CouponCampaign> findById(Long campaignId) {
        return campaignJpaRepository.findById(campaignId).map(CouponCampaignEntity::toDomain);
    }

    @Override
    public List<Long> findActiveIds() {
        return campaignJpaRepository.findActiveIds();
    }

    @Override
    public boolean increaseIssuedCountWithinQuota(Long campaignId, int count) {
        return campaignJpaRepository.increaseIssuedCountWithinQuota(campaignId, count) == 1;
    }
}
