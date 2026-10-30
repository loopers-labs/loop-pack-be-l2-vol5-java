package com.loopers.application.purchaseactivity;

import org.springframework.stereotype.Component;

@Component
public class NoOpPurchaseActivityProbe implements PurchaseActivityProbe {

    @Override
    public void afterActivitySaved(long orderId, long productId) {
    }

    @Override
    public void beforeCommit(long orderId) {
    }
}
