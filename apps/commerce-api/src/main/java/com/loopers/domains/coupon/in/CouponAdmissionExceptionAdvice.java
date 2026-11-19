package com.loopers.domains.coupon.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.domains.coupon.app.AdmissionClosedException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 쿠폰 수용 닫힘만 Retry-After 30을 붙인다(D135). 공통 advice의 다른 503(결제 등) 응답 계약은 그대로 둔다.
// 공통 advice는 업무 패키지에 의존하지 않으므로 쿠폰 전용 처리기를 쿠폰 in에 두고 공통 advice보다 먼저 적용한다.
@Order(-2)
@RestControllerAdvice(basePackages = "com.loopers.domains.coupon.in")
public class CouponAdmissionExceptionAdvice {
    @ExceptionHandler(AdmissionClosedException.class)
    public ResponseEntity<ApiResponse<Object>> admissionClosed(AdmissionClosedException exception) {
        return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "30")
            .body(ApiResponse.fail("Service Unavailable", exception.getMessage()));
    }
}
