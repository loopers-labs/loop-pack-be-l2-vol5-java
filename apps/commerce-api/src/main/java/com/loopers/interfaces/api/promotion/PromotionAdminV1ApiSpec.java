package com.loopers.interfaces.api.promotion;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Promotion Admin V1 API", description = "프로모션 관리자 API")
public interface PromotionAdminV1ApiSpec {

    /** POST /api-admin/v1/promotions */
    @Operation(summary = "쿠폰 발급 프로모션 생성",
        description = "promotion·쿠폰 발급 설정·남은 수량을 함께 만들고 바로 ACTIVE 로 연다. 종류는 COUPON_ISSUE, 1인당 한도는 1 만 받는다.")
    ApiResponse<PromotionV1Dto.PromotionResponse> create(Long requesterId, PromotionV1Dto.CreateRequest request);
}
