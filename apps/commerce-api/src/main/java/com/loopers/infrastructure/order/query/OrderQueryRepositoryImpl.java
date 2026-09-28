package com.loopers.infrastructure.order.query;

import com.loopers.application.order.query.OrderQueryRepository;
import com.loopers.application.order.query.OrderView;
import com.loopers.domain.order.OrderStatus;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.loopers.domain.order.QOrderItemModel.orderItemModel;
import static com.loopers.domain.order.QOrderModel.orderModel;

/** TB-06 주문 헤더를 먼저 읽고, TB-07 품목을 주문 ID 로 한 번에 읽어 조립한다. 한 BC 안 (DR-31). */
@RequiredArgsConstructor
@Component
public class OrderQueryRepositoryImpl implements OrderQueryRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<OrderView.Detail> find(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return fetchDetails(selectHeaders().where(orderModel.id.eq(orderId))).stream().findFirst();
    }

    /** 설계 3-7-A FR-ORDER-03: TB-06.user_id, created_at desc, id desc. */
    @Override
    public PageResult<OrderView.Detail> findPageByUserId(Long userId, PageQuery query) {
        List<OrderView.Detail> items = fetchDetails(selectHeaders()
            .where(orderModel.userId.eq(userId))
            .orderBy(orderModel.createdAt.desc(), orderModel.id.desc())
            .offset(query.offset())
            .limit(query.size()));
        Long total = queryFactory.select(orderModel.count())
            .from(orderModel)
            .where(orderModel.userId.eq(userId))
            .fetchOne();
        return PageResult.of(items, query, total == null ? 0 : total);
    }

    /** 설계 3-7-A FR-ADMIN-ORDER-01: 1단계 distinct user_id 를 페이지로 자름 / 2단계 user_id IN, 묶음 안 created_at desc, id desc. */
    @Override
    public PageResult<OrderView.BuyerGroup> findBuyerGroupPage(PageQuery query) {
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
        Map<Long, List<OrderView.Detail>> byBuyer = buyerIds.isEmpty()
            ? Map.of()
            : fetchDetails(selectHeaders()
                .where(orderModel.userId.in(buyerIds))
                .orderBy(orderModel.createdAt.desc(), orderModel.id.desc()))
                .stream()
                .collect(Collectors.groupingBy(OrderView.Detail::userId));
        List<OrderView.BuyerGroup> groups = new ArrayList<>();
        for (Long userId : buyerIds) {
            groups.add(new OrderView.BuyerGroup(userId, byBuyer.getOrDefault(userId, List.of())));
        }
        return PageResult.of(groups, query, total == null ? 0 : total);
    }

    private JPAQuery<Tuple> selectHeaders() {
        return queryFactory.select(
                orderModel.id, orderModel.userId, orderModel.status, orderModel.totalAmount,
                orderModel.paidAmount, orderModel.confirmedAt, orderModel.createdAt)
            .from(orderModel);
    }

    /** 헤더 순서를 유지한 채 품목을 붙인다. */
    private List<OrderView.Detail> fetchDetails(JPAQuery<Tuple> headerQuery) {
        List<Tuple> headers = headerQuery.fetch();
        Map<Long, List<OrderView.Item>> items = fetchItems(headers.stream().map(h -> h.get(orderModel.id)).toList());
        return headers.stream()
            .map(h -> {
                Long id = h.get(orderModel.id);
                OrderStatus status = h.get(orderModel.status);
                return new OrderView.Detail(
                    id,
                    h.get(orderModel.userId),
                    status == null ? null : status.name(),
                    h.get(orderModel.totalAmount),
                    h.get(orderModel.paidAmount),
                    h.get(orderModel.confirmedAt),
                    items.getOrDefault(id, List.of()),
                    h.get(orderModel.createdAt));
            })
            .toList();
    }

    private Map<Long, List<OrderView.Item>> fetchItems(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<Tuple> rows = queryFactory.select(
                orderItemModel.order.id, orderItemModel.productId, orderItemModel.quantity, orderItemModel.unitPrice)
            .from(orderItemModel)
            .where(orderItemModel.order.id.in(orderIds))
            .orderBy(orderItemModel.id.asc())
            .fetch();
        Map<Long, List<OrderView.Item>> byOrder = new LinkedHashMap<>();
        for (Tuple row : rows) {
            Integer quantity = row.get(orderItemModel.quantity);
            Long unitPrice = row.get(orderItemModel.unitPrice);
            byOrder.computeIfAbsent(row.get(orderItemModel.order.id), k -> new ArrayList<>())
                .add(new OrderView.Item(row.get(orderItemModel.productId), quantity, unitPrice, unitPrice * quantity));
        }
        return byOrder;
    }
}
