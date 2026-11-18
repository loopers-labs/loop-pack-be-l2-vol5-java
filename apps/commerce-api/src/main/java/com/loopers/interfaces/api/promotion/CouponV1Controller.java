package com.loopers.interfaces.api.promotion;

import com.loopers.application.promotion.query.CouponReader;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class CouponV1Controller implements CouponV1ApiSpec {

    private final CouponReader couponReader;

    @GetMapping("/api/v1/coupons")
    @Override
    public ApiResponse<List<PromotionV1Dto.MyCouponResponse>> listMyCoupons(@RequesterId Long requesterId) {
        var coupons = couponReader.listMyCoupons(requesterId).stream().map(PromotionV1Dto.MyCouponResponse::from).toList();
        return ApiResponse.success(coupons);
    }
}
