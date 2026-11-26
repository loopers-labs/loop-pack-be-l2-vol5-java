package com.loopers.domains.order.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.domains.order.app.UnresolvedRefundCaseView;
import java.time.Instant;

// 결제 대기 항목과 같이 null도 그대로 내보낸다. 거래 키는 값 대신 유무만 준다. 담당 종료 시각이 null이면 담당이 열려 있다.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnresolvedRefundCaseResponse(long orderId, String providerOrderId, String providerId, long amount,
    String moneyStatus, String refundActor, String owner, String ownerReason, Instant ownerChangedAt, Instant ownerClosedAt,
    String priorProviderStatus, boolean hasTransactionKey, Instant openedAt, long elapsedSeconds, Instant handoffAt,
    Instant promiseAt, int checkAttempts, Instant nextCheckAt, Instant refundRequestedAt, Instant concludedAt,
    Instant lastCheckAt, String lastCheckOutcome, String lastCheckCause) {
    static UnresolvedRefundCaseResponse from(UnresolvedRefundCaseView view) {
        return new UnresolvedRefundCaseResponse(view.orderId(), view.providerOrderId(), view.providerId(), view.amount(),
            view.moneyStatus(), view.refundActor(), view.owner(), view.ownerReason(), view.ownerChangedAt(),
            view.ownerClosedAt(), view.priorProviderStatus(), view.hasTransactionKey(), view.openedAt(), view.elapsedSeconds(),
            view.handoffAt(), view.promiseAt(), view.checkAttempts(), view.nextCheckAt(), view.refundRequestedAt(),
            view.concludedAt(), view.lastCheckAt(), view.lastCheckOutcome(), view.lastCheckCause());
    }
}
