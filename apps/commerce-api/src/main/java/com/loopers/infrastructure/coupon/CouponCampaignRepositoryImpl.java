package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {
    private final CouponCampaignJpaRepository jpaRepository;

    @Override
    public CouponCampaignModel save(CouponCampaignModel campaign) {
        return jpaRepository.save(campaign);
    }

    @Override
    public Optional<CouponCampaignModel> findById(Long campaignId) {
        return jpaRepository.findById(campaignId);
    }

    @Override
    public boolean increaseIssuedCount(Long campaignId) {
        return jpaRepository.increaseIssuedCount(campaignId, ZonedDateTime.now()) == 1;
    }
}
