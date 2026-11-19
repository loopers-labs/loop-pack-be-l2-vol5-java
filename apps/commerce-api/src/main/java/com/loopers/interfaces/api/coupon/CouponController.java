package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.Requester;
import com.loopers.support.paging.PageRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class CouponController implements CouponApiSpec {

    private final CouponIssueFacade couponIssueFacade;
    private final CouponFacade couponFacade;

    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    @Override
    public ResponseEntity<ApiResponse<CouponDto.IssueResponse>> issue(
        @Requester Long userId,
        @PathVariable Long campaignId
    ) {
        CouponIssueInfo info = couponIssueFacade.issue(campaignId, userId);
        return ResponseEntity.status(statusOf(info)).body(ApiResponse.success(CouponDto.IssueResponse.from(info)));
    }

    @GetMapping("/coupons")
    @Override
    public ApiResponse<PageResponse<CouponDto.CouponResponse>> getMyList(
        @Requester Long userId,
        @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE) int size
    ) {
        return ApiResponse.success(PageResponse.from(couponFacade.getMyList(userId, page, size), CouponDto.CouponResponse::from));
    }

    // 202 는 실패가 아니다. 당첨 기록은 DB 에 있고 쿠폰 저장만 늦어진 상태다.
    private static HttpStatus statusOf(CouponIssueInfo info) {
        return switch (info.status()) {
            case ISSUED -> HttpStatus.CREATED;
            case EXISTING -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
        };
    }
}
