package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.MyCouponInfo;
import com.loopers.coupon.application.port.out.CouponPort;
import com.loopers.coupon.application.port.out.UserCouponPort;
import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.UserCoupon;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 내 쿠폰 목록 (GET /api/v1/coupons, D-7). DB만 본다 — Redis가 멈춰도 동작한다 (과제 "기존 DB 결과 조회는 유지").
 * 사용자 쿠폰을 읽고, 쿠폰 규칙은 id로 한 번에 읽어 붙인다 (N+1 없음).
 */
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class MyCouponQueryService {

    private final UserCouponPort userCouponPort;
    private final CouponPort couponPort;

    public List<MyCouponInfo> getMyCoupons(Long userId) {
        List<UserCoupon> userCoupons = userCouponPort.findByUserId(userId);
        Map<Long, Coupon> coupons = couponPort.findAllByIds(userCoupons.stream().map(UserCoupon::getCouponId).distinct().toList())
            .stream().collect(Collectors.toMap(Coupon::getId, Function.identity()));
        return userCoupons.stream()
            .map(userCoupon -> MyCouponInfo.of(userCoupon, coupons.get(userCoupon.getCouponId())))
            .toList();
    }
}
