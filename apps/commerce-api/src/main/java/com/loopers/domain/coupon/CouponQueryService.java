package com.loopers.domain.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 내 쿠폰 조회. DB만 읽는다(Redis를 쓰지 않으므로 Redis가 죽어도 유지된다). */
@RequiredArgsConstructor
@Component
public class CouponQueryService {
    private final CouponRepository couponRepository;

    /** 발급 시각 내림차순, 같으면 couponId 내림차순. 요청한 사용자의 쿠폰만 돌려준다. */
    @Transactional(readOnly = true)
    public List<CouponModel> findMyCoupons(Long userId) {
        return couponRepository.findAllByUserIdOrderByIssuedAtDesc(userId);
    }
}
