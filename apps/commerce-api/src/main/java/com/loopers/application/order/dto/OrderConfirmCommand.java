package com.loopers.application.order.dto;

/**
 * 결제 시작 요청. 금액은 요청에 없다. 저장된 주문 합계로 정한다. 카드 값은 합성 값이며 저장하지 않고, 문자열 표현에 드러내지
 * 않는다.
 */
public record OrderConfirmCommand(Long userId, Long orderId, String attemptKey, String cardType, String cardNo) {
    @Override
    public String toString() {
        return "OrderConfirmCommand[userId=" + userId + ", orderId=" + orderId + ", attemptKey=" + attemptKey
            + ", cardType=" + cardType + ", cardNo=****]";
    }
}
