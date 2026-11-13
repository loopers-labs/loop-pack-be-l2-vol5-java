package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    Optional<CouponCampaign> lockCampaign(long campaignId);

    boolean campaignExists(long campaignId);

    Optional<CouponReservation> findReservation(long campaignId, long userId);

    List<Long> reservedUserIds(long campaignId);

    List<CouponReservation> findWaiting(List<Long> campaignIds, int limit);

    CouponReservation saveReservation(CouponReservation reservation);

    OwnedCoupon saveCoupon(OwnedCoupon coupon);

    Optional<OwnedCoupon> findCoupon(long reservationId);

    long countOwned(long userId);

    List<OwnedCoupon> findOwned(long userId, int page, int size);
}
