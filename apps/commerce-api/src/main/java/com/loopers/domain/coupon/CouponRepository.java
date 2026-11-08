package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    Coupon insert(Coupon coupon);

    Optional<Coupon> find(Long campaignId, Long userId);

    List<Coupon> findAllOwnedBy(Long userId);
}
