package com.loopers.domains.order.app;

import java.time.Instant;

public record UnresolvedPaymentView(long orderId, String providerOrderId, String providerId, String providerStatus,
    Instant startedAt, long elapsedSeconds, Integer lookupAttempts, Instant nextLookupAt, Anomaly latestAnomaly) implements UnresolvedItem {
    public record Anomaly(String kind, String summary, Instant lastSeenAt) { }
}
