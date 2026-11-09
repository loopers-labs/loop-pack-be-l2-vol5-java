package com.loopers.domain.purchase;

/**
 * 이미 처리한 사실과 충돌하는 이벤트. 같은 eventId에 다른 주문·수량·확정 시각이 오거나, 같은 orderId에 다른 eventId가 온 경우다.
 * 같은 사실의 재전달(정상 no-op)과 구분해 오류로 드러내고, 구매 활동을 더하지 않는다.
 */
public class OrderEventConflictException extends RuntimeException {

    public OrderEventConflictException(String message) {
        super(message);
    }
}
