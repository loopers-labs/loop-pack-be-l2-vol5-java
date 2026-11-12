package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {

    private final CouponCampaignJpaRepository jpaRepository;

    @Override
    public CouponCampaignModel save(CouponCampaignModel campaign) {
        return jpaRepository.save(campaign);
    }

    @Override
    public Optional<CouponCampaignModel> findById(long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public int incrementIssuedCountIfAvailable(long id) {
        return jpaRepository.incrementIssuedCountIfAvailable(id);
    }
}
