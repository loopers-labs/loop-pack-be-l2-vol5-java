package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class CouponQueryFacade {
    private final CouponQueryService queryService;

    public List<CouponInfo> getMyCoupons(Long userId) {
        return queryService.findMyCoupons(userId).stream().map(CouponInfo::from).toList();
    }
}
