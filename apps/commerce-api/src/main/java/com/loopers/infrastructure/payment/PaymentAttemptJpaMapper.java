package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;

final class PaymentAttemptJpaMapper {

    private PaymentAttemptJpaMapper() {}

    static PaymentAttempt toDomain(PaymentAttemptJpaEntity entity) {
        return PaymentAttempt.reconstitute(
            entity.getId(),
            entity.getPaymentAttemptKey(),
            entity.getOrderId(),
            entity.getProviderOrderId(),
            entity.getTransactionKey(),
            entity.getAmount(),
            entity.getStatus(),
            entity.getConflictReason(),
            entity.getConflictDetectedAt()
        );
    }

    static PaymentAttemptJpaEntity toNewEntity(PaymentAttempt attempt) {
        return PaymentAttemptJpaEntity.create(
            attempt.getPaymentAttemptKey(),
            attempt.getOrderId(),
            attempt.getProviderOrderId(),
            attempt.getAmount(),
            attempt.getStatus(),
            attempt.getTransactionKey(),
            attempt.getConflictReason(),
            attempt.getConflictDetectedAt()
        );
    }
}
