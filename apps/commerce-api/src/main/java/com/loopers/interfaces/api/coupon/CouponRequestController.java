package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.queue.CouponRequestService;
import com.loopers.interfaces.api.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CouponRequestController {
    private final CouponRequestService service;

    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issue-requests")
    public ResponseEntity<ApiResponse<?>> submit(
            @RequestHeader(value = "X-USER-ID", required = false) Long requester,
            @PathVariable("campaignId") long campaignId) {
        var result = service.submit(requester, campaignId);
        return switch (result) {
            case CouponRequestService.ExistingCoupon existing ->
                    ResponseEntity.ok(ApiResponse.success(new IssuedResponse(existing.couponId())));
            case CouponRequestService.QueuedRequest ignored ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(new RequestResponse(
                            "쿠폰 신청이 접수되었습니다. 잠시 후 쿠폰 목록을 확인해 주세요.")));
        };
    }

    public record RequestResponse(String message) {
    }

    public record IssuedResponse(long couponId) {
    }

}
