package com.loopers.domain.purchase;

public record ProcessedOrderConfirmation(String eventId, long orderId) {
    public static ProcessedOrderConfirmation create(String eventId, long orderId) {
        return new ProcessedOrderConfirmation(eventId, orderId);
    }
}
