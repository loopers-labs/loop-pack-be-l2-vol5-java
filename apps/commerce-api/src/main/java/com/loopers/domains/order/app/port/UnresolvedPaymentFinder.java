package com.loopers.domains.order.app.port;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface UnresolvedPaymentFinder {
    // 주문이 PAYMENT_PENDING인 PG 결제 중 상태가 providerStatuses에 들고 startedAtOrBefore 이전에 시작한 것을 시작 시각 오름차순으로 준다.
    List<Row> find(Set<String> providerStatuses, Instant startedAtOrBefore, long offset, int limit);

    record Row(long orderId, String providerOrderId, String providerId, String providerStatus, Instant startedAt,
        Integer lookupAttempts, Instant nextLookupAt, String anomalyKind, String anomalySummary, Instant anomalyLastSeenAt) { }
}
