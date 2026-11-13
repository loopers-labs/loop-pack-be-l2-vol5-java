package com.loopers.domain.coupon;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CouponRepository {

    Coupon insert(Coupon coupon);

    List<Coupon> insertAll(List<Coupon> coupons);

    Optional<Coupon> find(Long campaignId, Long userId);

    List<Coupon> findAllOwnedBy(Long userId);

    int maxIssueNumber(Long campaignId);

    Set<Long> issuedUserIdsAmong(Long campaignId, Collection<Long> userIds);

    List<Coupon> findIssuedAfterId(Long campaignId, long afterId, int limit);
}
