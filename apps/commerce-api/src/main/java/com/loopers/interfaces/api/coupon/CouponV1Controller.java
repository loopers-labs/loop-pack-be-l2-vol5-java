package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CouponV1Controller {

    private final CouponFacade couponFacade;

    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> issue(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long campaignId
    ) {
        CouponFacade.IssueResult result = couponFacade.issue(campaignId, userId, Instant.now());
        HttpStatus status = result instanceof CouponFacade.IssueResult.Accepted ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(CouponV1Dto.IssueResponse.from(result)));
    }

    @GetMapping("/coupons")
    public ApiResponse<List<CouponV1Dto.CouponResponse>> findMine(@RequestHeader("X-USER-ID") Long userId) {
        return ApiResponse.success(CouponV1Dto.CouponResponse.listOf(couponFacade.findMine(userId)));
    }
}
