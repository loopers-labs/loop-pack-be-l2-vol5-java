package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueQueueFacade;
import com.loopers.application.coupon.CouponIssueQueueResult;
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

/**
 * 쿠폰 발급 대기열 접수와 본인 작업 조회. 202는 작업을 저장했다는 뜻이며 처리 시작 시점을 약속하지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CouponIssueRequestV1Controller {

    private final CouponIssueQueueFacade couponIssueQueueFacade;

    @PostMapping("/coupon-campaigns/{campaignId}/issue-requests")
    public ResponseEntity<ApiResponse<CouponIssueRequestV1Dto.IssueRequestResponse>> submit(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long campaignId
    ) {
        validateUserId(userId);
        CouponIssueQueueResult result = couponIssueQueueFacade.submit(campaignId, userId);
        return switch (result) {
            case CouponIssueQueueResult.Accepted accepted -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(CouponIssueRequestV1Dto.IssueRequestResponse.from(accepted.request())));
            case CouponIssueQueueResult.Completed completed -> ResponseEntity.ok(
                ApiResponse.success(CouponIssueRequestV1Dto.IssueRequestResponse.from(completed.request())));
            case CouponIssueQueueResult.CouponAlreadyIssued issued -> ResponseEntity.ok(
                ApiResponse.success(CouponIssueRequestV1Dto.IssueRequestResponse.from(issued.coupon())));
        };
    }

    @GetMapping("/coupon-issue-requests/{requestId}")
    public ApiResponse<CouponIssueRequestV1Dto.IssueRequestResponse> getOwn(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable String requestId
    ) {
        validateUserId(userId);
        return ApiResponse.success(
            CouponIssueRequestV1Dto.IssueRequestResponse.from(couponIssueQueueFacade.getOwn(requestId, userId)));
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
    }
}
