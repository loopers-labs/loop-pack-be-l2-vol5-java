package com.loopers.domain.purchase;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchaseActivityService {

    private final PurchaseActivityRepository purchaseActivityRepository;

    public void recordPurchase(ConfirmedPurchase purchase) {
        purchaseActivityRepository.saveReceipt(purchase);
        purchaseActivityRepository.saveActivities(purchase);
    }

    public ReceiptCollision collide(ConfirmedPurchase purchase) {
        return purchase.collideWith(
            purchaseActivityRepository.findReceiptByEventId(purchase.eventId()),
            purchaseActivityRepository.findReceiptByOrderId(purchase.orderId()));
    }

    public void hold(OrderEventHold hold) {
        purchaseActivityRepository.saveHold(hold);
    }
}
