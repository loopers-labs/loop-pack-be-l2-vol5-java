package com.loopers.domain.coupon;

public interface CouponRepository {

    /**
     * 저장 후 영속 객체를 반환한다. 호출자는 반환 객체에서 ID·혜택을 복사한다.
     * BaseEntity 의 초기 ID 때문에 merge 경로의 PK 조회가 생길 수 있으며, 이는 (campaign_id, user_id) 중복 재조회가 아니다.
     */
    CouponModel save(CouponModel coupon);
}
