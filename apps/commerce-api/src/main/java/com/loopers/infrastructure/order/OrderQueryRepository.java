package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.loopers.infrastructure.order.QOrderEntity.orderEntity;

@Repository
@RequiredArgsConstructor
class OrderQueryRepository {

    private final JPAQueryFactory queryFactory;

    List<OrderEntity> findPage(Long userId, OrderStatus status, int offset, int limit) {
        return queryFactory.selectFrom(orderEntity)
            .where(conditions(userId, status))
            .orderBy(orderEntity.id.desc())
            .offset(offset)
            .limit(limit)
            .fetch();
    }

    private BooleanBuilder conditions(Long userId, OrderStatus status) {
        BooleanBuilder conditions = new BooleanBuilder();
        if (userId != null) {
            conditions.and(orderEntity.userId.eq(userId));
        }
        if (status != null) {
            conditions.and(orderEntity.status.eq(status));
        }
        return conditions;
    }
}
