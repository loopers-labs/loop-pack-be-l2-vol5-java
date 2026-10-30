package com.loopers.application.purchase;

import com.loopers.domain.purchase.AlreadyHeld;
import com.loopers.domain.purchase.ConfirmedPurchase;
import com.loopers.domain.purchase.FactAlreadyRecorded;
import com.loopers.domain.purchase.OrderEventHold;
import com.loopers.domain.purchase.PurchaseReading;
import com.loopers.domain.purchase.ReceiptCollision;
import com.loopers.domain.purchase.ReceivedRecord;
import com.loopers.domain.purchase.UnreadableReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseActivityHandler {

    private final PurchaseActivityTransactions transactions;
    private final PurchaseActivityMetrics metrics;

    public PurchaseOutcome handle(PurchaseReading reading, ReceivedRecord received) {
        return switch (reading) {
            case PurchaseReading.Readable(ConfirmedPurchase purchase) -> recordPurchase(purchase, received);
            case PurchaseReading.Unreadable(UnreadableReason reason) -> hold(OrderEventHold.unreadable(reason, received));
        };
    }

    private PurchaseOutcome recordPurchase(ConfirmedPurchase purchase, ReceivedRecord received) {
        try {
            transactions.recordPurchase(purchase);
            metrics.recorded(purchase.confirmedAt());
            return PurchaseOutcome.RECORDED;
        } catch (FactAlreadyRecorded collided) {
            ReceiptCollision collision = transactions.collide(purchase);
            return switch (collision) {
                case SAME_FACT -> {
                    metrics.handled(PurchaseOutcome.SAME_FACT);
                    yield PurchaseOutcome.SAME_FACT;
                }
                case EVENT_CONFLICT, ORDER_CONFLICT -> hold(OrderEventHold.conflict(collision, purchase, received));
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
                hold.reason(), hold.eventId(), hold.orderId(), hold.received().key(), hold.received().position());
        } catch (AlreadyHeld held) {
            outcome = PurchaseOutcome.ALREADY_HELD;
        }
        metrics.handled(outcome, hold.reason());
        return outcome;
    }
}
