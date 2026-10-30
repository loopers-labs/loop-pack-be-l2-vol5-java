package com.loopers.application.activity;

import com.loopers.domain.activity.AlreadyHeld;
import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.FactAlreadyRecorded;
import com.loopers.domain.activity.OrderEventHold;
import com.loopers.domain.activity.PurchaseReading;
import com.loopers.domain.activity.ReceiptCollision;
import com.loopers.domain.activity.ReceivedRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseActivityHandler {

    private final PurchaseActivityTransactions transactions;
    private final PurchaseActivityMetrics metrics;

    public PurchaseOutcome handle(PurchaseReading reading, ReceivedRecord record) {
        return switch (reading) {
            case PurchaseReading.Readable readable -> record(readable.purchase(), record);
            case PurchaseReading.Unreadable unreadable -> hold(OrderEventHold.unreadable(unreadable.reason(), record));
        };
    }

    private PurchaseOutcome record(ConfirmedPurchase purchase, ReceivedRecord record) {
        try {
            transactions.record(purchase);
            metrics.handled(PurchaseOutcome.RECORDED);
            return PurchaseOutcome.RECORDED;
        } catch (FactAlreadyRecorded collided) {
            ReceiptCollision collision = transactions.collide(purchase);
            return switch (collision) {
                case SAME_FACT -> {
                    metrics.handled(PurchaseOutcome.SAME_FACT);
                    yield PurchaseOutcome.SAME_FACT;
                }
                case EVENT_CONFLICT, ORDER_CONFLICT -> hold(OrderEventHold.conflict(collision, purchase, record));
                case NOT_RECORDED -> throw collided;
            };
        }
    }

    private PurchaseOutcome hold(OrderEventHold hold) {
        PurchaseOutcome outcome;
        try {
            transactions.hold(hold);
            outcome = PurchaseOutcome.HELD;
            log.warn("주문 이벤트를 반영하지 않고 보류했다: reason={}, eventId={}, orderId={}, key={}, position={}",
                hold.reason(), hold.eventId(), hold.orderId(), hold.record().key(), hold.record().position());
        } catch (AlreadyHeld held) {
            outcome = PurchaseOutcome.ALREADY_HELD;
        }
        metrics.handled(outcome, hold.reason());
        return outcome;
    }
}
