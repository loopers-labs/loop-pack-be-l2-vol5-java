package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
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
    public Optional<Order> findByIdWithItems(Long orderId) {
        return orderJpaRepository.findWithItemsById(orderId);
    }

    @Override
    public Optional<Order> findByIdForUpdate(Long orderId) {
        return Optional.ofNullable(queryFactory
            .selectFrom(order)
            .where(order.id.eq(orderId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne());
    }

    @Override
    public int finishIfPaymentPending(Long orderId, Order.OrderStatus status, long paidAmount) {
        return (int) queryFactory
            .update(order)
            .set(order.status, status)
            .set(order.paidAmount, paidAmount)
            .set(order.updatedAt, ZonedDateTime.now())
            .where(order.id.eq(orderId), order.status.eq(Order.OrderStatus.PAYMENT_PENDING))
            .execute();
    }

    private BooleanExpression userIdEq(Long userId) {
        return userId == null ? null : order.userId.eq(userId);
    }
}