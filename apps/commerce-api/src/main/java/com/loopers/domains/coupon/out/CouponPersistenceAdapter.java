package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.CouponIssueLookup;
import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class CouponPersistenceAdapter implements CouponRepository {
    private final CouponCampaignJpaRepository couponCampaignJpaRepository;
    private final IssuedCouponJpaRepository issuedCouponJpaRepository;

    public CouponPersistenceAdapter(CouponCampaignJpaRepository couponCampaignJpaRepository,
        IssuedCouponJpaRepository issuedCouponJpaRepository) {
        this.couponCampaignJpaRepository = couponCampaignJpaRepository;
        this.issuedCouponJpaRepository = issuedCouponJpaRepository;
    }

    @Override
    public Optional<CouponIssueLookup> findForIssue(long campaignId, long userId) {
        return Optional.empty();
    }

    @Override
    public int increaseIssued(long campaignId) {
        return 0;
    }

    @Override
    public IssuedCoupon insert(long campaignId, long userId, Instant issuedAt) {
        return null;
    }

    @Override
    public List<IssuedCoupon> findByUser(long userId) {
        return List.of();
    }
}
