package com.loopers.couponissue.adapter.out.persistence;

import com.loopers.couponissue.application.port.out.CouponIssuePort;
import com.loopers.couponissue.domain.IssuableCoupon;
import com.loopers.couponissue.domain.UserCoupon;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class CouponIssuePersistenceAdapter implements CouponIssuePort {

    private final UserCouponJpaRepository userCouponJpaRepository;
    private final IssuableCouponJpaRepository issuableCouponJpaRepository;
    private final CampaignQuotaJpaRepository campaignQuotaJpaRepository;

    @Override
    public boolean hasIssued(Long campaignId, Long userId) {
        return userCouponJpaRepository.existsByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public List<IssuableCoupon> findCoupons(Long campaignId) {
        return issuableCouponJpaRepository.findByCampaignIdOrderByIdAsc(campaignId);
    }

    @Override
    public void saveAll(List<UserCoupon> userCoupons) {
        userCouponJpaRepository.saveAll(userCoupons);
        userCouponJpaRepository.flush();
    }

    @Override
    public boolean increaseIssuedCountIfAvailable(Long campaignId) {
        return campaignQuotaJpaRepository.increaseIssuedCountIfAvailable(campaignId) == 1;
    }
}
