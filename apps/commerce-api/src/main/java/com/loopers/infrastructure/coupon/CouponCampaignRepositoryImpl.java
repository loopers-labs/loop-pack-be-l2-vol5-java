package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

import static com.loopers.domain.coupon.QCouponCampaign.couponCampaign;

@RequiredArgsConstructor
@Component
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {
    private final CouponCampaignJpaRepository couponCampaignJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public CouponCampaign save(CouponCampaign campaign) {
        return couponCampaignJpaRepository.save(campaign);
    }

    @Override
    public Optional<CouponCampaign> findById(Long id) {
        return couponCampaignJpaRepository.findById(id);
    }

    @Override
    public int increaseIssuedCountIfAvailable(Long campaignId) {
        return (int) queryFactory
            .update(couponCampaign)
            .set(couponCampaign.issuedCount, couponCampaign.issuedCount.add(1))
            .set(couponCampaign.updatedAt, ZonedDateTime.now())
            .where(
                couponCampaign.id.eq(campaignId),
                couponCampaign.issuedCount.lt(couponCampaign.totalQuota)
            )
            .execute();
    }
}
