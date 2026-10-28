package com.loopers.purchase.application.port.out;

import com.loopers.purchase.domain.ConsumedOrderConfirmation;
import com.loopers.purchase.domain.OrderConfirmedMessage;

import java.util.Optional;

public interface PurchaseActivityPort {

    /**
     * eventId 또는 주문 ID로 이미 있는 완료 기록을 찾는다 — 같은 주문의 다른 eventId도 잡기 위해 둘 다 본다.
     */
    Optional<ConsumedOrderConfirmation> findByEventIdOrOrderId(String eventId, Long orderId);

    /**
     * 완료 기록 1행과 품목 N행을 저장한다. 호출자 트랜잭션 안에서 부른다.
     */
    void saveAll(OrderConfirmedMessage message, String payload);
}
