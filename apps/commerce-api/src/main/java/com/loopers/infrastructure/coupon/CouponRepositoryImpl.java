package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {
    private final CouponJpaRepository couponJpaRepository;

    @Override
    public Coupon save(Coupon coupon) {
        return couponJpaRepository.save(coupon);
    }

    @Override
    public Coupon insert(Coupon coupon) {
        return couponJpaRepository.saveAndFlush(coupon);
    }

    @Override
    public Optional<Coupon> findById(Long id) {
        return couponJpaRepository.findById(id);
    }

    @Override
    public Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        return couponJpaRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public List<Coupon> findAllByCampaignId(Long campaignId) {
        return couponJpaRepository.findAllByCampaignId(campaignId);
    }

    @Override
    public Page<Coupon> findAllByUserId(Long userId, Pageable pageable) {
        return couponJpaRepository.findAllByUserId(userId, pageable);
    }
}
