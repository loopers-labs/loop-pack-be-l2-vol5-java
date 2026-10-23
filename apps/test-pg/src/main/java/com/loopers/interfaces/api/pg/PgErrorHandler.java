package com.loopers.interfaces.api.pg;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PgErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Error> invalidRequest(Exception exception) {
        // 바인딩 예외에는 카드 입력이 포함될 수 있어 예외 원문을 응답·로그에 사용하지 않는다.
        String message = exception instanceof IllegalArgumentException ? exception.getMessage() : "요청 형식이 올바르지 않습니다.";
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", message));
    }

    public record Error(String code, String message) {}
}
