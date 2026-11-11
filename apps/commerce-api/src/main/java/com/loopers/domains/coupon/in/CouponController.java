package com.loopers.domains.coupon.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.domains.coupon.app.CouponIssueService;
import com.loopers.domains.user.in.CustomerIdentity;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CouponController {
    private final CouponIssueService couponIssueService;
    private final CustomerIdentity customerIdentity;

    public CouponController(CouponIssueService couponIssueService, CustomerIdentity customerIdentity) {
        this.couponIssueService = couponIssueService;
        this.customerIdentity = customerIdentity;
    }

    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<CouponResponse>> issue(@RequestHeader(value = "X-USER-ID", required = false) String user,
        @PathVariable long campaignId) {
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/api/v1/coupons")
    public ApiResponse<List<CouponResponse>> mine(@RequestHeader(value = "X-USER-ID", required = false) String user) {
        return ApiResponse.success(List.of());
    }

    public record CouponResponse(long couponId, long campaignId, long discountAmount, Instant issuedAt) { }
}
