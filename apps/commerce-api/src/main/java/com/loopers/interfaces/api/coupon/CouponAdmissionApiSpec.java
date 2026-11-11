package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 쿠폰 신규 발급 수용의 수동 제어. 모든 프로필에 같은 경로로 등록하며 별도 인증·키가 없다.
 * cleanup·open 전에 운영자가 관련 실행·DB 종료와 token 범위를 확인해야 하며, API 는 그 확인을 자동 보증하지 않는다.
 */
@Tag(name = "Coupon Admission API", description = "쿠폰 신규 발급 수용 상태·닫기·자기 token 정리·명시적 열기")
public interface CouponAdmissionApiSpec {

    ApiResponse<CouponAdmissionDto.AdmissionResponse> status();

    ApiResponse<CouponAdmissionDto.AdmissionResponse> close();

    /** 닫힌 상태에서만 지정 token 을 제거한다. 열린 상태 409, 입력 오류 400 INVALID_REQUEST, Redis 실패 503(닫힘 유지). */
    ApiResponse<CouponAdmissionDto.CleanupResponse> cleanup(CouponAdmissionDto.CleanupRequest request);

    ApiResponse<CouponAdmissionDto.AdmissionResponse> open();
}
