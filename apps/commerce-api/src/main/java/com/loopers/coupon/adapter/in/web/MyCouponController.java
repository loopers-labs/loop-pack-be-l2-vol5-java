package com.loopers.coupon.adapter.in.web;

import com.loopers.coupon.adapter.in.web.dto.MyCouponDto;
import com.loopers.coupon.application.MyCouponQueryService;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Coupon V1 API", description = "고객용 쿠폰 API (X-USER-ID 필요)")
@RequiredArgsConstructor
@RestController
public class MyCouponController {

    private final MyCouponQueryService myCouponQueryService;

    @Operation(summary = "내 쿠폰 목록", description = "DB만 봅니다. 발급 처리 중(202)인 쿠폰은 consumer가 끝낸 뒤 보입니다.")
    @GetMapping("/api/v1/coupons")
    public ApiResponse<List<MyCouponDto.MyCouponResponse>> getMyCoupons(LoginUser loginUser) {
        return ApiResponse.success(myCouponQueryService.getMyCoupons(loginUser.id()).stream()
            .map(MyCouponDto.MyCouponResponse::from)
            .toList());
    }
}
