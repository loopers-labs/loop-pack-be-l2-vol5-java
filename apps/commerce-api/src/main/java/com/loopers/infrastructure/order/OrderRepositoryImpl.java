package com.loopers.infrastructure.order;

import com.loopers.domain.common.PageCondition;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.QOrder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {
    private static final QOrder order = QOrder.order;

    private final OrderJpaRepository orderJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Order save(Order order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<Order> find(Long id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public List<Order> findByUserId(Long userId, PageCondition page) {
        return queryFactory
            .selectFrom(order)
            .where(order.userId.eq(userId))
            .orderBy(order.createdAt.desc(), order.id.desc())
            .offset(page.offset())
            .limit(page.size())
            .fetch();
    }

    @Override
    public long countByUserId(Long userId) {
        return orderJpaRepository.countByUserId(userId);
    }

    @Override
    public List<Order> findAll(Long userId, PageCondition page) {
        return queryFactory
            .selectFrom(order)
            .where(userFilter(userId))
            .orderBy(order.createdAt.desc(), order.id.desc())
            .offset(page.offset())
            .limit(page.size())
            .fetch();
    }

    @Override
    public long countAll(Long userId) {
        return queryFactory
            .select(order.count())
            .from(order)
            .where(userFilter(userId))
            .fetchOne();
    }

    private BooleanExpression userFilter(Long userId) {
        return userId == null ? null : order.userId.eq(userId);
    }
}
