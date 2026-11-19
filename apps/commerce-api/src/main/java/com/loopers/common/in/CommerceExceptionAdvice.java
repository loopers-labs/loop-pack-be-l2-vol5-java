package com.loopers.common.in;

import com.loopers.common.error.CapacityExceededException;
import com.loopers.common.error.InvalidValueException;
import com.loopers.common.error.NotFoundException;
import com.loopers.common.error.RuleViolationException;
import com.loopers.common.error.TemporarilyUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(-1)
@RestControllerAdvice(basePackages = {"com.loopers.domains.brand.in", "com.loopers.domains.product.in",
    "com.loopers.domains.point.in", "com.loopers.domains.order.in", "com.loopers.domains.coupon.in"})
public class CommerceExceptionAdvice {
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> notFound(NotFoundException exception) {
        return ResponseEntity.status(404).body(ApiResponse.fail("Not Found", exception.getMessage()));
    }

    @ExceptionHandler(ArithmeticException.class)
    public ResponseEntity<ApiResponse<Object>> overflow(ArithmeticException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("Bad Request", "금액 표현 범위를 초과했습니다."));
    }

    @ExceptionHandler(InvalidValueException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(InvalidValueException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("Bad Request", exception.getMessage()));
    }

    @ExceptionHandler(RuleViolationException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(RuleViolationException exception) {
        return ResponseEntity.status(409).body(ApiResponse.fail("Conflict", exception.getMessage()));
    }

    // 지금 자리가 없다는 뜻이다. Retry-After는 다시 시도할 시점의 안내일 뿐 발급을 보장하지 않는다(D135).
    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<ApiResponse<Object>> tooManyRequests(CapacityExceededException exception) {
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, "1")
            .body(ApiResponse.fail("Too Many Requests", exception.getMessage()));
    }

    @ExceptionHandler(TemporarilyUnavailableException.class)
    public ResponseEntity<ApiResponse<Object>> unavailable(TemporarilyUnavailableException exception) {
        return ResponseEntity.status(503).body(ApiResponse.fail("Service Unavailable", exception.getMessage()));
    }

    // 잠금 대기 시간 초과·교착은 업무 거절(409·404)이 아닌 일시적 기술 실패다. 원인은 로그로 남기고 응답에는 노출하지 않는다.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> lockFailure(PessimisticLockingFailureException exception) {
        log.warn("행 잠금 획득 실패", exception);
        return ResponseEntity.status(503).body(ApiResponse.fail("Service Unavailable", "잠시 후 다시 시도해 주세요."));
    }
}
