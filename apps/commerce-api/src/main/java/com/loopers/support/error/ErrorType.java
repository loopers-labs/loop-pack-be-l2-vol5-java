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
    CONCURRENCY_CONFLICT("Concurrency Conflict", "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    /** W8 D-7: 쿠폰이 다 나갔다 — 업무 상태 충돌 */
    COUPON_SOLD_OUT("Coupon Sold Out", "쿠폰이 모두 발급되었습니다."),
    /** W8 D-7: 대기열에서 아직 입장하지 않았다 */
    QUEUE_NOT_ADMITTED("Queue Not Admitted", "아직 입장 차례가 아닙니다. 대기열에 등록해 주세요."),
    /** W8 D-7: 발급 기간이 아니다 */
    CAMPAIGN_NOT_OPEN("Campaign Not Open", "발급 기간이 아닙니다."),
    /** W8 D-7: 줄이 가득 찼거나 요청 속도 제한을 넘었다 — 나중에 다시 시도하면 될 수 있다 */
    TOO_MANY_REQUESTS("Too Many Requests", "요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    /** W8 D-7: 판정 상태를 알 수 없거나(Redis) 처리 자리가 없다(Bulkhead) — 우회하지 않고 다시 시도하게 한다 */
    SERVICE_UNAVAILABLE("Service Unavailable", "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final String code;
    private final String message;
}
