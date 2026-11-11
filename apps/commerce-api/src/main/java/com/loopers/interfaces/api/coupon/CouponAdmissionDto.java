package com.loopers.interfaces.api.coupon;

import java.util.List;

public class CouponAdmissionDto {

    /** 앱의 신규 수용 허용 상태. 실행 종료나 Redis 건강의 증거가 아니다. */
    public record AdmissionResponse(boolean accepting) {
    }

    /** 운영자가 종료·소유를 확인한 기존 token 목록. 내용 검증은 서비스가 하며 오류는 INVALID_REQUEST 다. */
    public record CleanupRequest(List<String> tokens) {
    }

    public record CleanupResponse(long removedCount) {
    }
}
