package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.QOrderModel;
import com.loopers.infrastructure.support.QueryDslSort;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {

    private static final QOrderModel ORDER = QOrderModel.orderModel;

    private final OrderJpaRepository orderJpaRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 즉시 flush해 품목 식별자와 생성·수정 시각이 응답을 만들기 전에 반영되게 한다.
     */
    @Override
    public OrderModel save(OrderModel order) {
        return orderJpaRepository.saveAndFlush(order);
    }

    @Override
    public Optional<OrderModel> findById(Long orderId) {
        return orderJpaRepository.findById(orderId);
    }

    /**
     * 목록과 개수를 따로 센다. userId가 null이면 구매자 조건을 붙이지 않는다 (관리자 조회, ORD-06).
     * 정렬은 호출자가 넘긴 Sort를 그대로 쓴다 (목록은 id desc).
     */
    @Override
    public Page<OrderModel> findAll(Long userId, Pageable pageable) {
        List<OrderModel> content = queryFactory.selectFrom(ORDER)
            .where(userIdEq(userId))
            .orderBy(QueryDslSort.of(pageable.getSort(), ORDER))
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        Long total = queryFactory.select(ORDER.count())
            .from(ORDER)
            .where(userIdEq(userId))
            .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private BooleanExpression userIdEq(Long userId) {
        return userId == null ? null : ORDER.userId.eq(userId);
    }
}
