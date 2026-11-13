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
    public CouponCampaign save(CouponCampaign couponCampaign) {
        return couponCampaignJpaRepository.save(couponCampaign);
    }

    @Override
    public Optional<CouponCampaign> findById(Long id) {
        return couponCampaignJpaRepository.findById(id);
    }

    @Override
    public int increaseIssuedCount(Long id) {
        return couponCampaignJpaRepository.increaseIssuedCount(id);
    }
}
