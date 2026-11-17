package com.loopers.coupon.adapter.out.persistence;

import com.loopers.coupon.application.port.out.UserCouponPort;
import com.loopers.coupon.domain.UserCoupon;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class UserCouponPersistenceAdapter implements UserCouponPort {

    private final UserCouponJpaRepository userCouponJpaRepository;

    @Override
    public List<UserCoupon> findByUserId(Long userId) {
        return userCouponJpaRepository.findByUserIdOrderByIdDesc(userId);
    }

    @Override
    public List<UserCoupon> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        return userCouponJpaRepository.findByCampaignIdAndUserIdOrderByCouponIdAsc(campaignId, userId);
    }
}
