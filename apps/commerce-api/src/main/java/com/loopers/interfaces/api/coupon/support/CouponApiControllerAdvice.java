package com.loopers.interfaces.api.coupon.support;

import com.loopers.application.coupon.support.CouponAdmissionException;
import com.loopers.application.coupon.support.CouponCounterException;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.coupon.exception.CouponIssueLockBusyException;
import com.loopers.domain.support.error.DomainException;
import com.loopers.interfaces.api.ApiErrorMapper;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.loopers.interfaces.api.coupon.controller")
@RequiredArgsConstructor
public class CouponApiControllerAdvice {
    private final ApiErrorMapper mapper;

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> domain(DomainException failure) {
        String code = failure.getErrorCode().name();
        if (!code.startsWith("COUPON_")) { return legacy(mapper.map(failure.getErrorCode()), failure.getMessage()); }
        HttpStatus status = code.startsWith("COUPON_INVALID_") ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return error(status, code, failure.getMessage());
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> application(ApplicationException failure) {
        String code = failure.getErrorCode().name();
        if (!code.startsWith("COUPON_")) { return legacy(mapper.map(failure.getErrorCode()), failure.getMessage()); }
        return error(mapper.map(failure.getErrorCode()).getStatus(), code, failure.getMessage());
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> admission(CouponAdmissionException failure) {
        HttpStatus status = switch (failure.getErrorCode()) {
            case COUPON_CAPACITY_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case COUPON_ADMISSION_UNAVAILABLE, COUPON_ISSUE_OUTCOME_UNKNOWN -> HttpStatus.SERVICE_UNAVAILABLE;
            case COUPON_RECOVERY_REJECTED -> HttpStatus.CONFLICT;
        };
        return error(status, failure.getErrorCode().name(), failure.getMessage());
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> slot(CouponSlotBusyException failure) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "COUPON_SLOT_BUSY", "쿠폰 발급 자리가 일시 경합 중입니다.");
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> lock(CouponIssueLockBusyException failure) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "COUPON_LOCK_BUSY", "쿠폰 캠페인 잠금이 일시 경합 중입니다.");
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> counter(CouponCounterException failure) {
        log.warn("Coupon aggregate operation failed", failure);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "COUPON_COUNTER_UNAVAILABLE", "쿠폰 집계를 확인할 수 없습니다.");
    }

    private static ResponseEntity<ApiResponse<?>> legacy(ErrorType type, String message) {
        return error(type.getStatus(), type.getCode(), message);
    }

    private static ResponseEntity<ApiResponse<?>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(code, message));
    }
}
