package com.loopers.interfaces.api;

import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.product.ProductNotFoundException;
import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.common.RuleViolationException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(basePackages = {"com.loopers.interfaces.api.brand", "com.loopers.interfaces.api.product"})
public class CommerceExceptionAdvice {
    @ExceptionHandler({BrandNotFoundException.class, ProductNotFoundException.class})
    public ResponseEntity<ApiResponse<Object>> notFound(RuntimeException exception) {
        return ResponseEntity.status(404).body(ApiResponse.fail("Not Found", exception.getMessage()));
    }

    @ExceptionHandler(InvalidValueException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(InvalidValueException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("Bad Request", exception.getMessage()));
    }

    @ExceptionHandler(RuleViolationException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(RuleViolationException exception) {
        return ResponseEntity.status(409).body(ApiResponse.fail("Conflict", exception.getMessage()));
    }
}
