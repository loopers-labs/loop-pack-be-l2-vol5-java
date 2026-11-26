package com.loopers.domains.order.app;

import java.time.Instant;

public record UnresolvedRefundCaseView(long orderId, String providerOrderId, String providerId, long amount,
    String refundStatus, String priorProviderStatus, boolean hasTransactionKey, String reason, Instant openedAt,
    long elapsedSeconds, Instant handoffAt, Instant promiseAt, int checkAttempts, Instant nextCheckAt, Instant refundRequestedAt, Instant concludedAt)
    implements UnresolvedItem { }
