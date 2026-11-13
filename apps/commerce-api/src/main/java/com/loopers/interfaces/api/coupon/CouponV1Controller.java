package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.application.coupon.CouponQuery;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 발급과 보유 목록(docs/week8/decisions.md D-9, D-16). 수용 열기·닫기·남은 token 정리는 HTTP로 열지 않는다(D-11).
 */
@RequiredArgsConstructor
@RestController
public class CouponV1Controller implements CouponV1ApiSpec {

    private final CouponIssueFacade couponIssueFacade;
    private final CouponQuery couponQuery;

    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    @Override
    public ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable(value = "campaignId") Long campaignId
    ) {
        CouponIssueResult result = couponIssueFacade.issue(campaignId, userId);
        HttpStatus status = result.outcome() == CouponIssueResult.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(CouponV1Dto.CouponResponse.from(result.coupon())));
    }

    @GetMapping("/api/v1/coupons")
    @Override
    public ApiResponse<CouponV1Dto.CouponListResponse> getMyCoupons(
        @RequestHeader("X-USER-ID") Long userId
    ) {
        return ApiResponse.success(CouponV1Dto.CouponListResponse.from(couponQuery.listMine(userId)));
    }
}
