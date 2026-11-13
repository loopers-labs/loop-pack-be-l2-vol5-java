package com.loopers.application.coupon;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CouponFacade {
    private final IdentifyUser identifyUser;
    private final CouponRepository coupons;

    public List<Coupon> list(Long requester) {
        return coupons.findByUser(identifyUser.require(requester));
    }
}
