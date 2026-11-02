package com.loopers.domain.activity;

/**
 * 주문 확정 사실의 계약 위반: 값이 규칙에 맞지 않거나, 같은 eventId·orderId 로 이미 처리한 사실과 다르다.
 * 정상 재전달이 아니므로 추가 구매를 만들지 않고, 재시도하지 않으며, 소비를 멈춰 드러낸다.
 */
public class OrderConfirmationContractException extends RuntimeException {

    public OrderConfirmationContractException(String message) {
        super(message);
    }
}
