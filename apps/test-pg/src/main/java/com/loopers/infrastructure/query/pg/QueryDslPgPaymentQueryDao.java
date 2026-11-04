package com.loopers.infrastructure.query.pg;

import com.loopers.application.pg.query.PgLookupView;
import com.loopers.application.pg.query.PgPaymentQueryDao;
import com.loopers.application.pg.query.PgPaymentView;
import com.loopers.domain.pg.model.PgScenario;
import com.loopers.infrastructure.persistence.pg.entity.QPgTransactionJpaEntity;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueryDslPgPaymentQueryDao implements PgPaymentQueryDao {
    private static final QPgTransactionJpaEntity TX = QPgTransactionJpaEntity.pgTransactionJpaEntity;
    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<PgLookupView> findByKey(String transactionKey) {
        return select(TX.transactionKey.eq(transactionKey)).stream().findFirst();
    }

    @Override
    public List<PgLookupView> findByOrderId(String orderId) {
        return select(TX.orderId.eq(orderId));
    }

    private List<PgLookupView> select(BooleanExpression predicate) {
        return queryFactory.select(Projections.constructor(PgLookupRow.class,
                        TX.transactionKey, TX.orderId, TX.amount, TX.status,
                        TX.scenario.lookupResponseMode, TX.scenario.lookupResponseDelayMs))
                .from(TX).where(predicate).orderBy(TX.createdAt.asc(), TX.transactionKey.asc()).fetch().stream()
                .map(row -> new PgLookupView(new PgPaymentView(row.transactionKey(), row.orderId(), row.amount(), row.status()),
                        PgScenario.ResponseMode.valueOf(row.mode()), row.delayMs()))
                .toList();
    }

    public record PgLookupRow(String transactionKey, String orderId, long amount, String status, String mode, long delayMs) {}
}
