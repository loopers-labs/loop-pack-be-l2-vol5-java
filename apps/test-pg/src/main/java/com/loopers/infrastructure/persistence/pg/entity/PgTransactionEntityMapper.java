package com.loopers.infrastructure.persistence.pg.entity;

import com.loopers.domain.pg.model.PgStatus;
import com.loopers.domain.pg.model.PgTransaction;
import org.springframework.stereotype.Component;

@Component
public class PgTransactionEntityMapper {
    public PgTransactionJpaEntity toNewEntity(PgTransaction transaction) {
        PgTransactionJpaEntity entity = new PgTransactionJpaEntity();
        entity.transactionKey = transaction.transactionKey();
        entity.orderId = transaction.orderId();
        entity.amount = transaction.amount();
        entity.callbackUrl = transaction.callbackUrl();
        entity.status = transaction.status().name();
        entity.scenario = PgScenarioFields.from(transaction.scenario());
        entity.createdAt = transaction.createdAt();
        entity.dueAt = transaction.dueAt();
        return entity;
    }

    public PgTransaction toDomain(PgTransactionJpaEntity entity) {
        return new PgTransaction(entity.transactionKey, entity.orderId, entity.amount, entity.callbackUrl,
                PgStatus.valueOf(entity.status), entity.scenario.toDomain(), entity.createdAt, entity.dueAt);
    }
}
