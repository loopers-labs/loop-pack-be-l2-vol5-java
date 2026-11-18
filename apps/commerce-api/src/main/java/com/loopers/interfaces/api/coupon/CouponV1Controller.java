package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.application.coupon.facade.CouponFacade;
import com.loopers.application.coupon.facade.CouponIssueFacade;
import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class CouponV1Controller implements CouponV1ApiSpec {

    /** 다시 확인할 간격의 잠정값(초). 당첨은 곧 발급되고, 예비는 앞의 최종 실패가 있어야 바뀐다 (8주차 D-148) */
    private static final String WAITING_RETRY_AFTER = "1";
    private static final String RESERVE_RETRY_AFTER = "5";

    private final CouponIssueFacade couponIssueFacade;
    private final CouponFacade couponFacade;

    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    @Override
    public ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> requestIssue(LoginUser loginUser, @PathVariable Long campaignId) {
        CouponIssueInfo info = couponIssueFacade.request(campaignId, loginUser.id());
        return respond(info, isPending(info) ? HttpStatus.ACCEPTED : HttpStatus.OK);
    }

    @GetMapping("/api/v1/coupon-campaigns/{campaignId}/issues/me")
    @Override
    public ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> getMyIssue(LoginUser loginUser, @PathVariable Long campaignId) {
        return respond(couponIssueFacade.find(campaignId, loginUser.id()), HttpStatus.OK);
    }

    @GetMapping("/api/v1/coupons")
    @Override
    public ApiResponse<PageResponse<CouponV1Dto.CouponResponse>> getMyCoupons(
        LoginUser loginUser,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(
            couponFacade.getMyCoupons(loginUser.id(), PageQuery.of(page, size)),
            CouponV1Dto.CouponResponse::from
        ));
    }

    private static boolean isPending(CouponIssueInfo info) {
        return info.state() == IssueState.WAITING || info.state() == IssueState.RESERVE;
    }

    private static ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> respond(CouponIssueInfo info, HttpStatus status) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (info.state() == IssueState.WAITING) {
            builder.header(HttpHeaders.RETRY_AFTER, WAITING_RETRY_AFTER);
        } else if (info.state() == IssueState.RESERVE) {
            builder.header(HttpHeaders.RETRY_AFTER, RESERVE_RETRY_AFTER);
        }
        return builder.body(ApiResponse.success(CouponV1Dto.IssueResponse.from(info)));
    }
}
