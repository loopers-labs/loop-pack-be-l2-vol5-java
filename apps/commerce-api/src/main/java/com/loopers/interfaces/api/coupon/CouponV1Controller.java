package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.support.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class CouponV1Controller implements CouponV1ApiSpec {

    private final CouponFacade couponFacade;

    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    @Override
    public ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(
        @CustomerId Long userId,
        @PathVariable(value = "campaignId") Long campaignId
    ) {
        CouponIssueResult result = couponFacade.issue(userId, campaignId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.success(CouponV1Dto.CouponResponse.from(result)));
    }

    @GetMapping("/api/v1/coupons")
    @Override
    public ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>> getMyCoupons(
        @CustomerId Long userId,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size,
        @RequestParam(value = "sort", required = false) String sort
    ) {
        return ApiResponse.success(PageResponse.of(
            couponFacade.getMyCoupons(userId, PageCommand.of(page, size), ListSort.from(sort)),
            CouponV1Dto.OwnedCouponResponse::from));
    }
}
