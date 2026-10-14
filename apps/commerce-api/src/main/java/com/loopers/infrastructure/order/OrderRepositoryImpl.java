package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.order.QOrder.order;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;
    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    @Override
    public Order save(Order order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<Order> findById(Long id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public List<Order> findAllByUserId(Long userId) {
        return orderJpaRepository.findAllByUserId(userId);
    }

    @Override
    public List<Order> findAllForAdmin(Long userId, int page, int size) {
        return queryFactory
            .selectFrom(order)
            .where(userIdEq(userId))
            .orderBy(order.createdAt.desc(), order.id.desc())
            .offset((long) page * size)
            .limit(size)
            .fetch();
    }

    @Override
    public int confirmIfDraft(Long orderId, Long userId, long paidAmount) {
        return (int) queryFactory
            .update(order)
            .set(order.status, Order.OrderStatus.CONFIRMED)
            .set(order.paidAmount, paidAmount)
            .set(order.updatedAt, ZonedDateTime.now())
            .where(
                order.id.eq(orderId),
                order.userId.eq(userId),
                order.status.eq(Order.OrderStatus.DRAFT)
            )
            .execute();
    }

    @Override
    public Order reload(Order target) {
        entityManager.refresh(target);
        return target;
    }

    private BooleanExpression userIdEq(Long userId) {
        return userId == null ? null : order.userId.eq(userId);
    }
}
