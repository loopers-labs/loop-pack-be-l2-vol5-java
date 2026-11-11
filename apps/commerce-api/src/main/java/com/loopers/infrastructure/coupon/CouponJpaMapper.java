package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;

final class CouponJpaMapper {

    private CouponJpaMapper() {}

    static Coupon toDomain(CouponJpaEntity entity) {
        return Coupon.reconstitute(entity.getId(), entity.getCampaign().getId(),
            entity.getUserId(), entity.getIssuedAt());
    }
}
