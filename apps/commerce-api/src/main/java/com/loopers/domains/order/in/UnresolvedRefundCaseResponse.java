package com.loopers.domains.order.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.domains.order.app.UnresolvedRefundCaseView;
import java.time.Instant;

// 결제 대기 항목과 같이 null도 그대로 내보낸다. 거래 키는 값 대신 유무만 준다.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnresolvedRefundCaseResponse(long orderId, String providerOrderId, String providerId, long amount,
    String refundStatus, String priorProviderStatus, boolean hasTransactionKey, String reason, Instant openedAt,
    long elapsedSeconds, Instant handoffAt, Instant promiseAt, int checkAttempts, Instant nextCheckAt,
    Instant refundRequestedAt, Instant concludedAt) {
    static UnresolvedRefundCaseResponse from(UnresolvedRefundCaseView view) {
        return new UnresolvedRefundCaseResponse(view.orderId(), view.providerOrderId(), view.providerId(), view.amount(),
            view.refundStatus(), view.priorProviderStatus(), view.hasTransactionKey(), view.reason(), view.openedAt(),
            view.elapsedSeconds(), view.handoffAt(), view.promiseAt(), view.checkAttempts(), view.nextCheckAt(),
            view.refundRequestedAt(), view.concludedAt());
    }
}
