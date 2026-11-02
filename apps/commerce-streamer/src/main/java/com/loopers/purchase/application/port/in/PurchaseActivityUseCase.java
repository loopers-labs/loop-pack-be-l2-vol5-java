package com.loopers.purchase.application.port.in;

import com.loopers.purchase.domain.OrderConfirmedMessage;

public interface PurchaseActivityUseCase {

    /**
     * 확정 이벤트 하나를 구매 활동으로 반영한다. 처음이면 저장, 같은 사실의 재전달이면 아무것도 바꾸지 않는다(no-op).
     * 충돌이면 ConflictingOrderConfirmationException. 정상 반환했을 때만 호출자가 ack한다.
     */
    Result consume(OrderConfirmedMessage message, String payload);

    enum Result { SAVED, DUPLICATE }
}
