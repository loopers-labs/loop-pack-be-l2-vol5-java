package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponQueryFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/coupons")
public class CouponV1Controller {

    private final CouponQueryFacade queryFacade;

    /** 현재 사용자의 보유 쿠폰 목록. 발급 시각 내림차순, couponId 내림차순. 본인 쿠폰만 반환한다. */
    @GetMapping
    public ApiResponse<CouponV1Dto.CouponListResponse> getMyCoupons(@LoginUserId Long userId) {
        return ApiResponse.success(CouponV1Dto.CouponListResponse.from(queryFacade.getMyCoupons(userId)));
    }
}
