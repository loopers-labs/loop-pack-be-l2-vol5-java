package com.loopers.purchaseactivity.domain;

import java.time.Instant;

public record PurchaseActivity(long orderId, long productId, int quantity, Instant confirmedAt) {
}
