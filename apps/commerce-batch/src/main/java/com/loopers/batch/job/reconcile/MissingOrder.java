package com.loopers.batch.job.reconcile;

import java.time.Instant;

public record MissingOrder(long orderId, Instant confirmedAt) {
}
