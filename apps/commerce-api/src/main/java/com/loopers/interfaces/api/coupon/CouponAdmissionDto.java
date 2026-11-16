package com.loopers.interfaces.api.coupon;

public class CouponAdmissionDto {

    /** 앱의 신규 수용 허용 상태. 실행 종료나 Redis 건강의 증거가 아니다. */
    public record AdmissionResponse(boolean accepting) {
    }
}
