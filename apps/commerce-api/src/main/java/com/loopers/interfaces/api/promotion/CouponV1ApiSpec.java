package com.loopers.interfaces.api.promotion;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Coupon V1 API", description = "쿠폰 고객 API")
public interface CouponV1ApiSpec {

    /** GET /api/v1/coupons */
    @Operation(summary = "내 쿠폰 목록", description = "발급받은 쿠폰 전부를 최근 발급 순으로 돌려준다. 페이지 없음.")
    ApiResponse<List<PromotionV1Dto.MyCouponResponse>> listMyCoupons(Long requesterId);
}
