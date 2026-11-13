package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
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
    public Optional<CouponCampaign> findById(Long id) {
        return couponCampaignJpaRepository.findById(id);
    }

    @Override
    public Optional<CouponCampaign> findByIdForUpdate(Long id) {
        return couponCampaignJpaRepository.findForUpdate(id);
    }
}
