package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponAdmissionFacade;
import com.loopers.application.coupon.CouponAdmissionFacade.ApplyInfo;
import com.loopers.application.coupon.CouponAdmissionFacade.ClaimInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 선착순 쿠폰 접수·상태·발급·보유 목록 (8주차 설계 §8-1). 사용자 식별은 기존 X-USER-ID 헤더 방식 그대로다.
 * 응답 코드는 "다음에 무엇을 하면 되는가"를 뜻한다 (§8-2). 실패는 ApiControllerAdvice 가 ErrorType 으로 매핑한다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class CouponV1Controller {

    private static final String USER_ID_HEADER = "X-USER-ID";
    static final String ACCEPTED_MESSAGE = "접수되었습니다. 접수는 발급 보장이 아니며, 상태 조회로 진행을 확인해 주세요.";

    private final CouponAdmissionFacade couponAdmissionFacade;

    /** 202 신규 접수 {seq} / 200 이미 접수 {seq} / 200 이미 발급 {couponId} (D13). 202 는 발급 보장이 아니라고 meta.message 에 적는다. */
    @PostMapping("/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<?>> apply(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "campaignId") Long campaignId
    ) {
        ApplyInfo info = couponAdmissionFacade.apply(campaignId, userId);
        return switch (info.outcome()) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(new CouponV1Dto.ApplyResponse(info.seq()), ACCEPTED_MESSAGE));
            case ALREADY_ACCEPTED -> ResponseEntity.ok(ApiResponse.success(new CouponV1Dto.ApplyResponse(info.seq())));
            case ALREADY_ISSUED -> ResponseEntity.ok(ApiResponse.success(new CouponV1Dto.CouponIdResponse(info.couponId())));
        };
    }

    @GetMapping("/coupon-campaigns/{campaignId}/issues/me")
    public ApiResponse<CouponV1Dto.StatusResponse> status(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "campaignId") Long campaignId
    ) {
        return ApiResponse.success(CouponV1Dto.StatusResponse.from(couponAdmissionFacade.status(campaignId, userId)));
    }

    /** 201 신규 발급 / 200 기존 발급. 같은 모양의 본문 {couponId} 다. */
    @PostMapping("/coupon-campaigns/{campaignId}/issues/claim")
    public ResponseEntity<ApiResponse<CouponV1Dto.CouponIdResponse>> claim(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "campaignId") Long campaignId
    ) {
        ClaimInfo info = couponAdmissionFacade.claim(campaignId, userId);
        HttpStatus status = info.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(new CouponV1Dto.CouponIdResponse(info.couponId())));
    }

    @GetMapping("/coupons")
    public ApiResponse<List<CouponV1Dto.CouponResponse>> myCoupons(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId
    ) {
        List<CouponV1Dto.CouponResponse> response = couponAdmissionFacade.myCoupons(userId).stream()
            .map(CouponV1Dto.CouponResponse::from)
            .toList();
        return ApiResponse.success(response);
    }
}
