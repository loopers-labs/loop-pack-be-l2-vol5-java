package com.loopers.domains.order.app.port;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface UnresolvedRefundCaseFinder {
    // openedAtOrBefore 이전에 열린 환불 확인 대상을 열린 시각 오름차순으로 준다. personOpenOnly면 사람 담당 중(담당 종료 전)만,
    // moneyStatuses가 비어 있지 않으면 그 돈의 상태만 준다(둘 다 주면 둘 다 맞는 대상). 거래 키는 값 대신 유무만 준다.
    List<Row> find(boolean personOpenOnly, Set<String> moneyStatuses, Instant openedAtOrBefore, long offset, int limit);

    record Row(long orderId, String providerOrderId, String providerId, long amount, String moneyStatus, String refundActor,
        String owner, String ownerReason, Instant ownerChangedAt, Instant ownerClosedAt, String priorProviderStatus,
        boolean hasTransactionKey, Instant openedAt, Instant handoffAt, Instant promiseAt, int checkAttempts,
        Instant nextCheckAt, Instant refundRequestedAt, Instant concludedAt, Instant lastCheckAt, String lastCheckOutcome,
        String lastCheckCause) { }
}
