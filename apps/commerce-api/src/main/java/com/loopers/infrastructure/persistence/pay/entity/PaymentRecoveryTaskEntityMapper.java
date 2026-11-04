package com.loopers.infrastructure.persistence.pay.entity;

import com.loopers.domain.pay.model.PaymentRecoveryTask;
import org.springframework.stereotype.Component;

@Component
public class PaymentRecoveryTaskEntityMapper {
    public PaymentRecoveryTaskJpaEntity toNewEntity(PaymentRecoveryTask task) {
        PaymentRecoveryTaskJpaEntity entity = new PaymentRecoveryTaskJpaEntity();
        entity.attemptKey = task.getAttemptKey();
        entity.state = task.getState().name();
        entity.executionId = task.getExecutionId();
        entity.usedRounds = task.getUsedRounds();
        entity.lastCheckedAt = task.getLastCheckedAt();
        entity.lastReason = task.getLastReason().name();
        entity.updatedAt = task.getUpdatedAt();
        return entity;
    }

    public PaymentRecoveryTask toDomain(PaymentRecoveryTaskJpaEntity entity) {
        return PaymentRecoveryTask.restore(entity.attemptKey, PaymentRecoveryTask.State.valueOf(entity.state),
                entity.executionId, entity.usedRounds, entity.lastCheckedAt,
                PaymentRecoveryTask.Reason.valueOf(entity.lastReason), entity.updatedAt);
    }
}
