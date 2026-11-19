package com.loopers.domains.coupon.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.domains.coupon.app.CouponIssueService;
import com.loopers.domains.coupon.app.IssuedCouponView;
import com.loopers.domains.user.in.CustomerIdentity;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
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

    // 새로 발급하면 201, 이미 받은 쿠폰이면 200과 같은 couponId다.
    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<CouponResponse>> issue(@RequestHeader(value = "X-USER-ID", required = false) String user,
        @PathVariable long campaignId) {
        CouponIssueService.IssueResult result = couponIssueService.issue(campaignId, customerIdentity.require(user));
        HttpStatus status = result.newlyIssued() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(CouponResponse.from(result.coupon())));
    }

    @GetMapping("/api/v1/coupons")
    public ApiResponse<List<CouponResponse>> mine(@RequestHeader(value = "X-USER-ID", required = false) String user) {
        long userId = customerIdentity.require(user);
        return ApiResponse.success(couponIssueService.listMine(userId).stream().map(CouponResponse::from).toList());
    }

    public record CouponResponse(long couponId, long campaignId, long discountAmount, Instant issuedAt) {
        static CouponResponse from(IssuedCouponView coupon) {
            return new CouponResponse(coupon.couponId(), coupon.campaignId(), coupon.discountAmount(), coupon.issuedAt());
        }
    }
}
