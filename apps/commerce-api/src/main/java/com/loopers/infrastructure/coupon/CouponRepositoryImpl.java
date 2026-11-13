package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository couponJpaRepository;

    /** BaseEntity 의 초기 ID 때문에 merge 경로를 타면 인자와 다른 객체가 돌아오므로 반환 객체를 그대로 전달한다. */
    @Override
    public CouponModel save(CouponModel coupon) {
        return couponJpaRepository.save(coupon);
    }
}
