package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Coupon V1 API", description = "Loopers 쿠폰 발급·보유 목록 API 입니다.")
public interface CouponV1ApiSpec {

    @Operation(
        summary = "쿠폰 발급 요청",
        description = "처음 발급이면 201, 이미 받은 사용자면 200과 같은 쿠폰을 반환합니다. "
            + "없는 캠페인은 404, 품절·비활성은 409, 지금 처리할 자리가 없으면 429, 신규 발급을 받을 수 없으면 503입니다."
    )
    ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "캠페인 ID", description = "발급받을 쿠폰 캠페인의 ID")
        Long campaignId
    );

    @Operation(
        summary = "내 쿠폰 목록 조회",
        description = "요청자의 쿠폰만 발급 시각 내림차순(같으면 쿠폰 ID 내림차순)으로 반환합니다. 혜택은 발급 순간의 값입니다."
    )
    ApiResponse<CouponV1Dto.CouponListResponse> getMyCoupons(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId
    );
}
