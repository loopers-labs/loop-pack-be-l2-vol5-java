package com.loopers.application.purchase;

import com.loopers.domain.purchase.ConfirmedPurchase;
import com.loopers.domain.purchase.OrderEventHold;
import com.loopers.domain.purchase.PurchaseActivityService;
import com.loopers.domain.purchase.ReceiptCollision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PurchaseActivityTransactions {

    private final PurchaseActivityService purchaseActivityService;

    @Transactional
    public void recordPurchase(ConfirmedPurchase purchase) {
        purchaseActivityService.recordPurchase(purchase);
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
