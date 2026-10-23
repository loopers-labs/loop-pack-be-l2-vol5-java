package com.loopers.application.order;

import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflict;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.domain.payment.PaymentObservation;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 관리자 주문 상세 — 주문·결제 시도에 충돌 목록을 더한다(docs/week5/design.md D-024). 관리자가 충돌을 보고 PG 화면에서 조치한다(D-010).
 */
public record OrderAdminInfo(OrderInfo order, List<Conflict> conflicts) {

    public record Conflict(
        PaymentConflictReason reason,
        PaymentAttemptStatus recordedStatus,
        PaymentObservation.Status observedStatus,
        String observedTransactionKey,
        Long observedAmount,
        ZonedDateTime firstDetectedAt,
        ZonedDateTime lastDetectedAt,
        ZonedDateTime resolvedAt
    ) {
        static Conflict from(PaymentConflict conflict) {
            return new Conflict(
                conflict.getReason(),
                conflict.getRecordedStatus(),
                conflict.getObservedStatus(),
                conflict.getObservedTransactionKey(),
                conflict.getObservedAmount(),
                conflict.getFirstDetectedAt(),
                conflict.getLastDetectedAt(),
                conflict.getResolvedAt()
            );
        }
    }
}
