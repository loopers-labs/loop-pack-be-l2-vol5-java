package com.loopers.domains.order.app.port;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface UnresolvedRefundCaseFinder {
    // 환불 확인 대상 중 상태가 statuses에 들고 openedAtOrBefore 이전에 열린 것을 열린 시각 오름차순으로 준다. 거래 키는 값 대신 유무만 준다.
    List<Row> find(Set<String> statuses, Instant openedAtOrBefore, long offset, int limit);

    record Row(long orderId, String providerOrderId, String providerId, long amount, String status,
        String priorProviderStatus, boolean hasTransactionKey, String reason, Instant openedAt, Instant handoffAt,
        Instant promiseAt, int checkAttempts, Instant nextCheckAt, Instant refundRequestedAt, Instant concludedAt) { }
}
