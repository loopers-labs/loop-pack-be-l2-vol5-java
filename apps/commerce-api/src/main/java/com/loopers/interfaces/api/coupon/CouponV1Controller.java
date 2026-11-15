package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.application.coupon.CouponIssueStatus;
import com.loopers.interfaces.api.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class CouponV1Controller {
    // required=false: 헤더 누락도 facade 검사에서 400으로 응답한다. 공통 오류 처리는 바꾸지 않는다.
    private static final String USER_HEADER = "X-USER-ID";

    private final CouponFacade couponFacade;

    /**
     * 새 발급은 DB commit 뒤 201, 기존 쿠폰은 같은 couponId의 200이다.
     * 작업 큐 모드에서 접수만 됐으면 202와 처리 중 상태다. 결과는 발급 상태 조회로 확인한다.
     */
    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<?>> issue(
        @RequestHeader(value = USER_HEADER, required = false) Long userId,
        @PathVariable Long campaignId
    ) {
        CouponIssueResult result = couponFacade.issue(userId, campaignId);
        if (result.pending()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(CouponV1Dto.IssueStatusResponse.pending(result.campaignId())));
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.success(CouponV1Dto.CouponResponse.from(result.coupon())));
    }

    /** 발급 상태: ISSUED(쿠폰) / PENDING / CANCELLED(이유) / NONE. */
    @GetMapping("/coupon-campaigns/{campaignId}/issues/me")
    public ApiResponse<CouponV1Dto.IssueStatusResponse> getMyIssueStatus(
        @RequestHeader(value = USER_HEADER, required = false) Long userId,
        @PathVariable Long campaignId
    ) {
        CouponIssueStatus status = couponFacade.getIssueStatus(userId, campaignId);
        return ApiResponse.success(CouponV1Dto.IssueStatusResponse.from(status));
    }

    @GetMapping("/coupons")
    public ApiResponse<List<CouponV1Dto.CouponResponse>> getMyCoupons(
        @RequestHeader(value = USER_HEADER, required = false) Long userId
    ) {
        return ApiResponse.success(couponFacade.getMyCoupons(userId).stream()
            .map(CouponV1Dto.CouponResponse::from)
            .toList());
    }
}
