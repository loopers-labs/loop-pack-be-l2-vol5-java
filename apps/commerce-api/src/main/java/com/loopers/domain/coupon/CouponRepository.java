package com.loopers.domain.coupon;

public interface CouponRepository {

    /** BaseEntity 초기 ID 때문에 merge 경로의 PK 조회가 생길 수 있으며, 이는 중복 재조회가 아니다. */
    CouponModel save(CouponModel coupon);
}
