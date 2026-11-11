package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Coupon V1 API", description = "고객 쿠폰 발급·보유 목록 API")
public interface CouponV1ApiSpec {

    /** 새 발급 commit 확인 시 201, 이미 보유한 쿠폰은 200·같은 couponId. 신규 수용 자리 없음 429, 수용 불가 503. */
    ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(Long userId, Long campaignId);

    ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>> getMyCoupons(
        Long userId, Integer page, Integer size, String sort);
}
