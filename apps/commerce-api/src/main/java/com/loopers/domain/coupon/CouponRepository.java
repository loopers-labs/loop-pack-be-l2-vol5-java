package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    Optional<Coupon> findByCampaignAndUser(long campaignId, long userId);

    List<Coupon> findByUser(long userId);

    /** 요청 중인 발급 트랜잭션에서 insert와 제약 검사를 수행한다. */
    Coupon insert(long campaignId, long userId, String benefitDescription);
}
