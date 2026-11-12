package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 쿠폰 신규 발급 수용의 수동 제어. 모든 프로필에 같은 경로로 등록하며 별도 인증·키가 없다.
 * open 전에 운영자가 관련 실행·DB 종료를 확인해야 하며, API 는 그 확인을 자동 보증하지 않는다. 대기 상태 정리 API 는 없다.
 */
@Tag(name = "Coupon Admission API", description = "쿠폰 신규 발급 수용 상태·닫기·명시적 열기")
public interface CouponAdmissionApiSpec {

    ApiResponse<CouponAdmissionDto.AdmissionResponse> status();

    ApiResponse<CouponAdmissionDto.AdmissionResponse> close();

    /** 운영 캠페인(commerce.coupon.waiting.campaign-id) 미설정이면 409 COUPON_ADMISSION_OPERATION_REJECTED. */
    ApiResponse<CouponAdmissionDto.AdmissionResponse> open();
}
