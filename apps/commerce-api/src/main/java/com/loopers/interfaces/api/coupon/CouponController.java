package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.domain.coupon.Coupon;
import com.loopers.interfaces.api.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class CouponController {
    private final CouponFacade facade;

    @GetMapping("/api/v1/coupons")
    public ApiResponse<List<CouponResponse>> list(@RequestHeader(value = "X-USER-ID", required = false) Long requester) {
        return ApiResponse.success(facade.list(requester).stream().map(CouponResponse::from).toList());
    }

    public record CouponResponse(long couponId, long campaignId, String benefitDescription, ZonedDateTime issuedAt) {
        static CouponResponse from(Coupon coupon) {
            return new CouponResponse(coupon.couponId(), coupon.campaignId(), coupon.benefitDescription(), coupon.issuedAt());
        }
    }
}
