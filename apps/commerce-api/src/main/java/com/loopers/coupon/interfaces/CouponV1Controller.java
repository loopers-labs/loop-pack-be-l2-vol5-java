package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.Requester;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class CouponV1Controller {

    private final CouponUseCase couponUseCase;

    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(
        Requester requester,
        @PathVariable Long campaignId
    ) {
        CouponUseCase.CouponIssueResult result = couponUseCase.issue(campaignId, requester.userId());
        CouponV1Dto.CouponResponse response = CouponV1Dto.CouponResponse.from(result.coupon());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(response));
    }

    @GetMapping("/coupons")
    public ApiResponse<List<CouponV1Dto.CouponResponse>> getMyCoupons(Requester requester) {
        List<CouponV1Dto.CouponResponse> coupons = couponUseCase.getMyCoupons(requester.userId()).stream()
            .map(CouponV1Dto.CouponResponse::from)
            .toList();
        return ApiResponse.success(coupons);
    }
}
