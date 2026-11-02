package com.loopers.purchaseactivity.domain;

import java.time.Instant;

public record PurchaseActivity(long orderId, long productId, int quantity, Instant confirmedAt) {
    public PurchaseActivity {
        if (orderId <= 0 || productId <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("orderId, productId and quantity must be positive");
        }
        if (confirmedAt == null) {
            throw new IllegalArgumentException("confirmedAt must not be null");
        }
        if (confirmedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("confirmedAt must preserve MySQL microsecond precision");
        }
    }
}
