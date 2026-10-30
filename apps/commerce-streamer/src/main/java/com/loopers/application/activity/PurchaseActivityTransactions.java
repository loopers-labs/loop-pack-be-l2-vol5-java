package com.loopers.application.activity;

import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.OrderEventHold;
import com.loopers.domain.activity.PurchaseActivityService;
import com.loopers.domain.activity.ReceiptCollision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PurchaseActivityTransactions {

    private final PurchaseActivityService purchaseActivityService;

    @Transactional
    public void record(ConfirmedPurchase purchase) {
        purchaseActivityService.record(purchase);
    }

    @Transactional(readOnly = true)
    public ReceiptCollision collide(ConfirmedPurchase purchase) {
        return purchaseActivityService.collide(purchase);
    }

    @Transactional
    public void hold(OrderEventHold hold) {
        purchaseActivityService.hold(hold);
    }
}
