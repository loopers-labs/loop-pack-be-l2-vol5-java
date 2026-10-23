package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 도메인에서 던지는 에러의 종류.
 *
 * <p>전송 계층(HTTP 등)에 대한 지식을 갖지 않는다. HTTP 상태 코드로의 변환은
 * {@code interfaces.api} 레이어의 책임이다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR("일시적인 오류가 발생했습니다."),
    INVALID_INPUT("잘못된 요청입니다."),
    NOT_FOUND("존재하지 않는 요청입니다."),
    FORBIDDEN("접근 권한이 없습니다."),
    /** 현재 상태와 충돌하는 범용 코드. 사용처는 메시지를 직접 지정한다. */
    CONFLICT("요청이 현재 상태와 충돌합니다."),

    /** 업무 규칙 위반 (모두 현재 상태와의 충돌) */
    ORDER_ALREADY_CONFIRMED("이미 확정된 주문입니다. 주문 내역에서 결과를 확인해 주세요."),
    OUT_OF_STOCK("재고가 부족합니다. 수량을 줄이거나 다른 상품을 선택해 주세요."),
    INSUFFICIENT_POINT("포인트 잔액이 부족합니다. 포인트를 충전한 뒤 다시 시도해 주세요."),
    PRODUCT_UNAVAILABLE("주문할 수 없는 상품입니다. 주문 상품을 확인해 주세요."),
    PAYMENT_ATTEMPT_CONFLICT("이미 결제 시도가 있는 주문이거나 다른 주문에 쓰인 시도 키입니다. 주문 내역에서 결제 상태를 확인해 주세요.");

    private final String message;

    /** 클라이언트에 노출되는 안정적인 에러 식별자. */
    public String getCode() {
        return name();
    }
}
