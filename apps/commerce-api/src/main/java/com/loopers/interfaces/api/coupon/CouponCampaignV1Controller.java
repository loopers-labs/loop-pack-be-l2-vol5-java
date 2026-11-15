package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.CouponIssueInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/coupon-campaigns")
public class CouponCampaignV1Controller {

    private final CouponIssueFacade issueFacade;

    /**
     * 현재 사용자(X-USER-ID)의 쿠폰을 발급한다. 요청 바디로 userId를 받지 않는다.
     * 최초 발급은 201, 이미 받은 쿠폰의 재요청은 200과 같은 couponId다.
     */
    @PostMapping("/{campaignId}/issues")
    public ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> issue(
        @PathVariable Long campaignId,
        @LoginUserId Long userId
    ) {
        CouponIssueInfo info = issueFacade.issue(campaignId, userId);
        HttpStatus status = info.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(CouponV1Dto.IssueResponse.from(info)));
    }
}
