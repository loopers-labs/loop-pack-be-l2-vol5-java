package com.loopers.infrastructure.query.pay;

import com.loopers.application.pay.query.PaymentMetricQueryDao;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.infrastructure.persistence.pay.entity.QPaymentAttemptJpaEntity;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class QueryDslPaymentMetricQueryDao implements PaymentMetricQueryDao {
    private static final QPaymentAttemptJpaEntity ATTEMPT = QPaymentAttemptJpaEntity.paymentAttemptJpaEntity;
    private final JPAQueryFactory queries;

    @Override
    @Transactional(readOnly = true)
    public OptionalLong countOverdueUnknown(Instant observedAt) {
        NumberExpression<Long> orders = ATTEMPT.orderId.count();
        NumberExpression<Long> knownDeadlines = ATTEMPT.responseDeadline.count();
        // DATETIME(6) 기한과 나노초 관찰 시각의 엄격한 부등식을 유지한다.
        Instant micros = observedAt.truncatedTo(ChronoUnit.MICROS);
        BooleanExpression pastDeadline = observedAt.equals(micros)
            ? ATTEMPT.responseDeadline.lt(micros) : ATTEMPT.responseDeadline.loe(micros);
        Tuple counts = queries.select(orders, knownDeadlines).from(ATTEMPT)
            .where(ATTEMPT.dispatchState.eq(PaymentAttempt.DispatchState.STARTED.name()),
                ATTEMPT.outcome.eq(PaymentAttempt.Outcome.UNKNOWN.name()),
                pastDeadline.or(ATTEMPT.responseDeadline.isNull()))
            .fetchOne();
        long overdue = counts.get(knownDeadlines);
        // COUNT(기한)은 NULL을 제외한다. 기한이 없는 미확인 주문이 하나라도 있으면 총수를 단정할 수 없다.
        return counts.get(orders) == overdue ? OptionalLong.of(overdue) : OptionalLong.empty();
    }
}
