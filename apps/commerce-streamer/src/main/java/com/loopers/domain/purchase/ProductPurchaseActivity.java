package com.loopers.domain.purchase;

import java.time.Instant;

public record ProductPurchaseActivity(long orderId, long productId, int quantity, Instant confirmedAt) {
    public static ProductPurchaseActivity create(long orderId, long productId, int quantity, Instant confirmedAt) {
        return new ProductPurchaseActivity(orderId, productId, quantity, confirmedAt);
    }
}
