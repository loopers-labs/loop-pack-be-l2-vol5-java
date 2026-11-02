package com.loopers.purchaseactivity.domain;

import java.time.Instant;

public record ProcessedOrderConfirmation(
        String eventId, long orderId, Instant confirmedAt, String itemsSignature) {
}
