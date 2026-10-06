package com.loopers.interfaces.api;

import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.product.ProductNotFoundException;
import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.common.RuleViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(-1)
@RestControllerAdvice(basePackages = {"com.loopers.interfaces.api.brand", "com.loopers.interfaces.api.product", "com.loopers.interfaces.api.point", "com.loopers.interfaces.api.order"})
public class CommerceExceptionAdvice {
    @ExceptionHandler({BrandNotFoundException.class, ProductNotFoundException.class, com.loopers.application.order.OrderNotFoundException.class})
    public ResponseEntity<ApiResponse<Object>> notFound(RuntimeException exception) {
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

    // 잠금 대기 시간 초과·교착은 업무 거절(409·404)이 아닌 일시적 기술 실패다. 원인은 로그로 남기고 응답에는 노출하지 않는다.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> lockFailure(PessimisticLockingFailureException exception) {
        log.warn("행 잠금 획득 실패", exception);
        return ResponseEntity.status(503).body(ApiResponse.fail("Service Unavailable", "잠시 후 다시 시도해 주세요."));
    }
}
