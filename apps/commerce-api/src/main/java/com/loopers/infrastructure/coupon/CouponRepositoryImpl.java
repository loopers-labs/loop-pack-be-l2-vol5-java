package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {
    private final CouponJpaRepository jpaRepository;

    @Override
    public CouponModel saveAndFlush(CouponModel coupon) {
        return jpaRepository.saveAndFlush(coupon);
    }

    @Override
    public Optional<CouponModel> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        return jpaRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public List<CouponModel> findAllByUserIdOrderByIssuedAtDesc(Long userId) {
        return jpaRepository.findAllByUserIdOrderByIssuedAtDescIdDesc(userId);
    }
}
