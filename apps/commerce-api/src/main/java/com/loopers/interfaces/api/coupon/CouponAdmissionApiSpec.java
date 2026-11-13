package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 신규 발급 수용 수동 제어. open 전 운영자 확인을 API 가 보증하지 않는다. */
@Tag(name = "Coupon Admission API", description = "쿠폰 신규 발급 수용 상태·닫기·명시적 열기")
public interface CouponAdmissionApiSpec {

    ApiResponse<CouponAdmissionDto.AdmissionResponse> status();

    ApiResponse<CouponAdmissionDto.AdmissionResponse> close();

    /** 운영 캠페인(commerce.coupon.waiting.campaign-id) 미설정이면 409 COUPON_ADMISSION_OPERATION_REJECTED. */
    ApiResponse<CouponAdmissionDto.AdmissionResponse> open();
}
