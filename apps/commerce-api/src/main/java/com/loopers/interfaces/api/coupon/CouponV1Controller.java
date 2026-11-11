package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CouponV1Controller {

    private final CouponIssueFacade couponIssueFacade;

    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long campaignId
    ) {
        validateUserId(userId);
        CouponIssueResult result = couponIssueFacade.issue(campaignId, userId);
        HttpStatus status = result.newlyIssued() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
            .body(ApiResponse.success(CouponV1Dto.CouponResponse.from(result.coupon())));
    }

    @GetMapping("/coupons")
    public ApiResponse<List<CouponV1Dto.CouponResponse>> getMyCoupons(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId
    ) {
        validateUserId(userId);
        List<CouponV1Dto.CouponResponse> response = couponIssueFacade.getMyCoupons(userId).stream()
            .map(CouponV1Dto.CouponResponse::from)
            .toList();
        return ApiResponse.success(response);
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
    }
}
