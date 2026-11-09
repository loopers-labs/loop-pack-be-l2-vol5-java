package com.loopers.interfaces.api.promotion;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Promotion V1 API", description = "프로모션 고객 API")
public interface PromotionV1ApiSpec {

    /** POST /api/v1/promotions/{promotionId}/issues */
    @Operation(summary = "선착순 쿠폰 발급",
        description = "ACTIVE 인 쿠폰 발급 프로모션에서 쿠폰 한 장을 받는다. 사용자당 한 장, 발급 한도를 넘지 않는다.")
    ApiResponse<PromotionV1Dto.IssuedCouponResponse> issue(Long requesterId, Long promotionId);
}
