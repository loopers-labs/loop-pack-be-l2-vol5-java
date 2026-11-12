package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/coupon-campaigns")
public class CouponV1Controller {

    private final CouponFacade couponFacade;

    @PostMapping("/{campaignId}/queue")
    public ApiResponse<CouponV1Dto.EnqueueResponse> enqueue(
        @PathVariable long campaignId,
        @RequestHeader("X-USER-ID") long userId
    ) {
        return ApiResponse.success(CouponV1Dto.EnqueueResponse.from(
            couponFacade.enqueue(campaignId, userId)
        ));
    }

    @GetMapping("/{campaignId}/queue/me")
    public ApiResponse<CouponV1Dto.QueueStatusResponse> getQueueStatus(
        @PathVariable long campaignId,
        @RequestHeader("X-USER-ID") long userId
    ) {
        return ApiResponse.success(CouponV1Dto.QueueStatusResponse.from(
            couponFacade.getQueueStatus(campaignId, userId)
        ));
    }

    @PostMapping("/{campaignId}/issues")
    public ApiResponse<CouponV1Dto.IssueResponse> issueCoupon(
        @PathVariable long campaignId,
        @RequestHeader("X-USER-ID") long userId
    ) {
        return ApiResponse.success(CouponV1Dto.IssueResponse.from(
            couponFacade.issueCoupon(campaignId, userId)
        ));
    }
}
