package com.loopers.domains.order.app;

import java.time.Instant;

public record UnresolvedRefundCaseView(long orderId, String providerOrderId, String providerId, long amount, String moneyStatus,
    String refundActor, String owner, String ownerReason, Instant ownerChangedAt, Instant ownerClosedAt,
    String priorProviderStatus, boolean hasTransactionKey, Instant openedAt, long elapsedSeconds, Instant handoffAt,
    Instant promiseAt, int checkAttempts, Instant nextCheckAt, Instant refundRequestedAt, Instant concludedAt)
    implements UnresolvedItem { }
