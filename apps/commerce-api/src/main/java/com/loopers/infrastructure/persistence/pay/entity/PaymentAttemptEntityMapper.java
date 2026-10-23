package com.loopers.infrastructure.persistence.pay.entity;

import com.loopers.domain.pay.model.PaymentAttempt;
import org.springframework.stereotype.Component;

@Component
public class PaymentAttemptEntityMapper {
    public PaymentAttemptJpaEntity toNewEntity(PaymentAttempt attempt) {
        PaymentAttemptJpaEntity entity = new PaymentAttemptJpaEntity();
        entity.attemptKey = attempt.getAttemptKey();
        entity.orderId = attempt.getOrderId();
        entity.amount = attempt.getAmount();
        entity.providerOrderId = attempt.getProviderOrderId();
        entity.dispatchState = attempt.getDispatchState().name();
        entity.outcome = attempt.getOutcome().name();
        entity.transactionKey = attempt.getTransactionKey();
        entity.rejection = attempt.getRejection().name();
        entity.conflict = attempt.getConflict().name();
        entity.createdAt = attempt.getCreatedAt();
        entity.scenario = PaymentScenarioFields.from(attempt.getScenario());
        return entity;
    }

    public PaymentAttempt toDomain(PaymentAttemptJpaEntity entity) {
        return PaymentAttempt.restore(entity.attemptKey, entity.orderId, entity.amount, entity.providerOrderId,
                entity.scenario.toDomain(), PaymentAttempt.DispatchState.valueOf(entity.dispatchState),
                PaymentAttempt.Outcome.valueOf(entity.outcome), entity.transactionKey,
                PaymentAttempt.Rejection.valueOf(entity.rejection), PaymentAttempt.Conflict.valueOf(entity.conflict),
                entity.version, entity.createdAt);
    }

    public long version(PaymentAttemptJpaEntity entity) {
        return entity.version;
    }

    public void apply(PaymentAttempt attempt, PaymentAttemptJpaEntity entity) {
        entity.dispatchState = attempt.getDispatchState().name();
        entity.outcome = attempt.getOutcome().name();
        entity.transactionKey = attempt.getTransactionKey();
        entity.rejection = attempt.getRejection().name();
        entity.conflict = attempt.getConflict().name();
    }
}
