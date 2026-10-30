package com.loopers.domain.activity;

public record OrderEventHold(ReceivedRecord record, String reason, String eventId, Long orderId) {

    public static OrderEventHold conflict(ReceiptCollision collision, ConfirmedPurchase purchase,
                                          ReceivedRecord record) {
        if (collision != ReceiptCollision.EVENT_CONFLICT && collision != ReceiptCollision.ORDER_CONFLICT) {
            throw new IllegalArgumentException("충돌이 아닌 판정은 보류하지 않는다: " + collision);
        }
        return new OrderEventHold(record, collision.name(), purchase.eventId(), purchase.orderId());
    }

    public static OrderEventHold unreadable(UnreadableReason reason, ReceivedRecord record) {
        return new OrderEventHold(record, reason.name(), null, null);
    }
}
