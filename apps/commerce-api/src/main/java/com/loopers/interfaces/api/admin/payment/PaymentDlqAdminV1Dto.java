package com.loopers.interfaces.api.admin.payment;

import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentDlqModel;

public class PaymentDlqAdminV1Dto {

    public record DlqEntry(
        Long id,
        Long orderId,
        String reason,
        PaymentAttemptStatus attemptStatus,
        boolean resolved,
        String resolvedAt,
        String resolveNote,
        String createdAt
    ) {
        public static DlqEntry from(PaymentDlqModel model) {
            return new DlqEntry(
                model.getId(),
                model.getOrderId(),
                model.getReason(),
                model.getAttemptStatus(),
                model.isResolved(),
                model.getResolvedAt() != null ? model.getResolvedAt().toString() : null,
                model.getResolveNote(),
                model.getCreatedAt().toString()
            );
        }
    }

    public record ResolveRequest(String note) {}
}
