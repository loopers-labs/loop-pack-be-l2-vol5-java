package com.loopers.domain.purchase;

public record OrderEventHold(ReceivedRecord received, String reason, String eventId, Long orderId) {

    public static OrderEventHold conflict(ReceiptCollision collision, ConfirmedPurchase purchase,
                                          ReceivedRecord received) {
        if (collision != ReceiptCollision.EVENT_CONFLICT && collision != ReceiptCollision.ORDER_CONFLICT) {
            throw new IllegalArgumentException("충돌이 아닌 판정은 보류하지 않는다: " + collision);
        }
        return new OrderEventHold(received, collision.name(), purchase.eventId(), purchase.orderId());
    }

    public static OrderEventHold unreadable(UnreadableReason reason, ReceivedRecord received) {
        return new OrderEventHold(received, reason.name(), null, null);
    }
}
