package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssuanceModel;
import com.loopers.domain.coupon.CouponIssuanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CouponIssuanceRepositoryImpl implements CouponIssuanceRepository {

    private final CouponIssuanceJpaRepository jpaRepository;

    @Override
    public CouponIssuanceModel save(CouponIssuanceModel issuance) {
        return jpaRepository.save(issuance);
    }

    @Override
    public Optional<CouponIssuanceModel> findByCampaignIdAndUserId(long campaignId, long userId) {
        return jpaRepository.findByCampaignIdAndUserId(campaignId, userId);
    }
}
