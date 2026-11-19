package com.loopers.interfaces.api.admin.coupon;

import com.loopers.application.coupon.CouponAdmissionFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 접수 gate 관리 (8주차 설계 §7-3·§8-1). 기존 /api-admin/** 경계(ROLE_ADMIN + CSRF) 아래라 보안 설정에 경로를 추가하지 않는다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/coupon-campaigns/{campaignId}/admission")
public class AdminCouponAdmissionV1Controller {

    private final CouponAdmissionFacade couponAdmissionFacade;

    @PostMapping("/open")
    public ApiResponse<Object> open(@PathVariable(value = "campaignId") Long campaignId) {
        couponAdmissionFacade.openGate(campaignId);
        return ApiResponse.success();
    }

    @PostMapping("/close")
    public ApiResponse<Object> close(@PathVariable(value = "campaignId") Long campaignId) {
        couponAdmissionFacade.closeGate(campaignId);
        return ApiResponse.success();
    }

    /** 읽기만 한다. Redis 를 읽지 못해도 gate 상태는 바꾸지 않고 redisUnavailable=true 로 답한다. */
    @GetMapping
    public ApiResponse<CouponV1Dto.AdmissionStatusResponse> status(@PathVariable(value = "campaignId") Long campaignId) {
        return ApiResponse.success(CouponV1Dto.AdmissionStatusResponse.from(couponAdmissionFacade.admissionStatus(campaignId)));
    }
}
