package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.order.QOrderModel.orderModel;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public OrderModel save(OrderModel order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<OrderModel> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return orderJpaRepository.findById(id);
    }

    @Override
    public PageResult<OrderModel> findPageByUserId(Long userId, PageQuery query) {
        List<OrderModel> items = queryFactory.selectFrom(orderModel)
            .where(orderModel.userId.eq(userId))
            .orderBy(orderModel.createdAt.desc(), orderModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        Long total = queryFactory.select(orderModel.count())
            .from(orderModel)
            .where(orderModel.userId.eq(userId))
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }

    @Override
    public PageResult<Long> findBuyerIdPage(PageQuery query) {
        List<Long> buyerIds = queryFactory.select(orderModel.userId)
            .from(orderModel)
            .groupBy(orderModel.userId)
            .orderBy(orderModel.userId.asc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        Long total = queryFactory.select(orderModel.userId.countDistinct())
            .from(orderModel)
            .fetchOne();
        return PageResult.of(buyerIds, query, total == null ? 0 : total);
    }

    @Override
    public List<OrderModel> findByUserIds(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return queryFactory.selectFrom(orderModel)
            .where(orderModel.userId.in(userIds))
            .orderBy(orderModel.createdAt.desc(), orderModel.id.desc())
            .fetch();
    }
}
