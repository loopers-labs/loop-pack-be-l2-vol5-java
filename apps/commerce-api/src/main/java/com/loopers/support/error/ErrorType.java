package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 오류 종류. HTTP 상태를 알지 않는다 (ADR-12).
 * HTTP 상태는 interfaces의 ApiControllerAdvice가 정한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR("Internal Server Error", "일시적인 오류가 발생했습니다."),
    BAD_REQUEST("Bad Request", "잘못된 요청입니다."),
    UNAUTHORIZED("Unauthorized", "사용자를 식별할 수 없습니다."),
    NOT_FOUND("Not Found", "존재하지 않는 요청입니다."),
    CONFLICT("Conflict", "이미 존재하는 리소스입니다.");

    private final String code;
    private final String message;
}
