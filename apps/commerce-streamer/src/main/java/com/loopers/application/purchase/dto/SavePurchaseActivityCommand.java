package com.loopers.application.purchase.dto;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.PurchaseLine;

import java.time.ZonedDateTime;
import java.util.List;

/** 확정된 주문을 구매 활동으로 저장하라는 입력. 값을 나르기만 하고, 정규화·검증은 {@link OrderConfirmation} 이 한다. */
public record SavePurchaseActivityCommand(String eventId, Long orderId, ZonedDateTime confirmedAt, List<PurchaseLine> lines) {

    public OrderConfirmation toConfirmation() {
        return new OrderConfirmation(eventId, orderId, confirmedAt, lines);
    }
}
