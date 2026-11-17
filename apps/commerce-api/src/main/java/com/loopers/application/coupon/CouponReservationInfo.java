package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponStatus;

public record CouponReservationInfo(long reservationId, long campaignId, long userId, CouponStatus status,
                                    Long couponId) {
    static CouponReservationInfo from(CouponReservation reservation, Long couponId) {
        return new CouponReservationInfo(reservation.getId(), reservation.getCampaign().getId(),
            reservation.getUser().getId(), reservation.getStatus(), couponId);
    }
}
