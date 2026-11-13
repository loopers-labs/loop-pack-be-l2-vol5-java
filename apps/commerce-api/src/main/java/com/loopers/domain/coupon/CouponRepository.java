package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CouponRepository {

    Coupon insert(Coupon coupon);

    Optional<Coupon> find(Long campaignId, Long userId);

    List<Coupon> findAllOwnedBy(Long userId);

    int maxIssueNumber(Long campaignId);

    Set<Integer> issuedNumbersBetween(Long campaignId, int from, int to);

    List<CouponReservation.Reservation> findIssuedAfter(Long campaignId, int afterNumber, int limit);
}
