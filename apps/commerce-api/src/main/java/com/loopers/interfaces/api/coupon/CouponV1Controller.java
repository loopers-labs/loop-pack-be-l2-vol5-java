package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponIssueOutcome;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.application.coupon.CouponWaitingResult;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.support.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class CouponV1Controller implements CouponV1ApiSpec {

    public static final String WAITING_ID_HEADER = "X-WAITING-ID";

    private final CouponFacade couponFacade;

    @CouponWaitingRateLimited
    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/waiting")
    @Override
    public ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> registerWaiting(
        @CustomerId Long userId,
        @PathVariable(value = "campaignId") Long campaignId
    ) {
        CouponWaitingResult result = couponFacade.registerWaiting(userId, campaignId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.success(CouponV1Dto.WaitingResponse.from(result.snapshot())));
    }

    @GetMapping("/api/v1/coupon-campaigns/{campaignId}/waiting")
    @Override
    public ApiResponse<CouponV1Dto.WaitingResponse> getWaiting(
        @CustomerId Long userId,
        @PathVariable(value = "campaignId") Long campaignId
    ) {
        return ApiResponse.success(CouponV1Dto.WaitingResponse.from(couponFacade.getWaiting(userId, campaignId).snapshot()));
    }

    /** 헤더는 MVC 필수 입력이 아니다. 기존 쿠폰 반환이 헤더 누락 400 보다 먼저라서 Facade 가 순서대로 판단한다. */
    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    @Override
    public ResponseEntity<ApiResponse<?>> issue(
        @CustomerId Long userId,
        @PathVariable(value = "campaignId") Long campaignId,
        @RequestHeader(value = WAITING_ID_HEADER, required = false) String waitingId
    ) {
        CouponIssueOutcome outcome = couponFacade.issue(userId, campaignId, waitingId);
        if (outcome.processing() != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(CouponV1Dto.WaitingResponse.from(outcome.processing())));
        }
        CouponIssueResult result = outcome.result();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.success(CouponV1Dto.CouponResponse.from(result)));
    }

    @GetMapping("/api/v1/coupons")
    @Override
    public ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>> getMyCoupons(
        @CustomerId Long userId,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size,
        @RequestParam(value = "sort", required = false) String sort
    ) {
        return ApiResponse.success(PageResponse.of(
            couponFacade.getMyCoupons(userId, PageCommand.of(page, size), ListSort.from(sort)),
            CouponV1Dto.OwnedCouponResponse::from));
    }
}
