package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {
    private final CouponCampaignJpaRepository campaigns;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CouponCampaign> lockById(long campaignId) {
        return campaigns.lockById(campaignId).map(CouponCampaignJpaEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void increaseIssuedCount(long campaignId) {
        // 같은 EntityManager가 앞서 잠근 영속 엔티티를 반환한다.
        campaigns.findById(campaignId).orElseThrow().increaseIssuedCount();
    }
}
