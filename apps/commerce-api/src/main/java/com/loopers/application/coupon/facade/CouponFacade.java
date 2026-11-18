package com.loopers.application.coupon.facade;

import com.loopers.application.coupon.dto.CouponInfo;
import com.loopers.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 발급된 쿠폰은 DB 사실이라 Redis 와 무관하게 조회된다 (8주차 D-147) */
@RequiredArgsConstructor
@Component
public class CouponFacade {

    private final CouponRepository couponRepository;

    @Transactional(readOnly = true)
    public Page<CouponInfo> getMyCoupons(Long userId, Pageable pageable) {
        return couponRepository.findMyCoupons(userId, pageable).map(CouponInfo::from);
    }
}
