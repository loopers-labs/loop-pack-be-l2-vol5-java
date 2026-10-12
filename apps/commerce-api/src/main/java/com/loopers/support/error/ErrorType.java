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
    CONFLICT("Conflict", "이미 존재하는 리소스입니다."),
    /** 잠금 대기 초과·교착 — 업무 거절(재고·잔액 부족)이 아니라 혼잡이다. 다시 시도하면 성공할 수 있다 (W3 ADR-W3-04). */
    CONCURRENCY_CONFLICT("Concurrency Conflict", "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final String code;
    private final String message;
}
