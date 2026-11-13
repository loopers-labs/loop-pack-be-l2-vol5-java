package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {

    private final CouponCampaignJpaRepository couponCampaignJpaRepository;

    @Override
    public CouponCampaign save(CouponCampaign campaign) {
        return couponCampaignJpaRepository.save(campaign);
    }

    @Override
    public Optional<CouponCampaign> findById(Long campaignId) {
        return couponCampaignJpaRepository.findById(campaignId);
    }

    @Override
    public long count() {
        return couponCampaignJpaRepository.count();
    }
}
