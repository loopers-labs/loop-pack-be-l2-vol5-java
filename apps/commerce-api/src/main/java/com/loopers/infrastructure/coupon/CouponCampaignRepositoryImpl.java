package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {

    private final CouponCampaignJpaRepository couponCampaignJpaRepository;

    @Override
    public CouponCampaignModel save(CouponCampaignModel campaign) {
        return couponCampaignJpaRepository.save(campaign);
    }

    @Override
    public int reserveOne(Long campaignId, ZonedDateTime now) {
        return couponCampaignJpaRepository.reserveOne(campaignId, now);
    }

    @Override
    public Optional<CouponCampaignIssueData> findIssueData(Long campaignId) {
        return couponCampaignJpaRepository.findIssueData(campaignId);
    }

    @Override
    public Optional<CouponCampaignModel> findById(Long campaignId) {
        return couponCampaignJpaRepository.findById(campaignId);
    }
}
