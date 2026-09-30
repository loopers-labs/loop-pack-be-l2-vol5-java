package com.loopers.infrastructure.query.ordering;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderItemView;
import com.loopers.application.ordering.query.OrderQueryDao;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderItemJpaEntity;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderJpaEntity;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderRecordJpaEntity;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// QueryDSL 기반 주문 조회 DAO
public class QueryDslOrderQueryDao implements OrderQueryDao {
    private static final QOrderJpaEntity ORDER = QOrderJpaEntity.orderJpaEntity;
    private static final QOrderRecordJpaEntity RECORD = QOrderRecordJpaEntity.orderRecordJpaEntity;
    private static final QOrderItemJpaEntity ITEM = QOrderItemJpaEntity.orderItemJpaEntity;

    private final JPAQueryFactory queryFactory;

    // 사용자 주문 목록 페이지 조회
    @Override
    @Transactional(readOnly = true)
    public PageResult<OrderView> findOrders(long userId, PageCriteria criteria) {
        Predicate ownedBy = ORDER.userId.eq(userId);
        List<OrderHeaderRow> headers = findHeaders(ownedBy, criteria);
        Map<Long, List<OrderItemView>> itemsByOrderId = findItemsByOrderIds(orderIdsOf(headers));
        List<OrderView> items = headers.stream()
            .map(header -> header.toView(itemsByOrderId.getOrDefault(header.orderId(), List.of())))
            .toList();
        return PageResult.of(items, criteria.page(), criteria.size(), countOrders(ownedBy));
    }

    // 단건 주문 조회
    @Override
    @Transactional(readOnly = true)
    public Optional<OrderView> findOrder(long orderId) {
        return findHeader(orderId).map(header -> header.toView(findItemsByOrderId(orderId)));
    }

    // 관리자용 전체 주문 페이지 조회
    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminOrderView> findAdminOrders(PageCriteria criteria) {
        List<OrderHeaderRow> headers = findHeaders(null, criteria);
        Map<Long, List<OrderItemView>> itemsByOrderId = findItemsByOrderIds(orderIdsOf(headers));
        List<AdminOrderView> items = headers.stream()
            .map(header -> header.toAdminView(itemsByOrderId.getOrDefault(header.orderId(), List.of())))
            .toList();
        return PageResult.of(items, criteria.page(), criteria.size(), countOrders(null));
    }

    // 관리자용 단건 주문 조회
    @Override
    @Transactional(readOnly = true)
    public Optional<AdminOrderView> findAdminOrder(long orderId) {
        return findHeader(orderId).map(header -> header.toAdminView(findItemsByOrderId(orderId)));
    }

    private long countOrders(Predicate condition) {
        Long count = queryFactory.select(ORDER.count())
            .from(ORDER)
            .where(condition)
            .fetchOne();
        return count == null ? 0L : count;
    }

    // 주문 헤더 페이지 조회
    private List<OrderHeaderRow> findHeaders(Predicate condition, PageCriteria criteria) {
        return selectHeaders()
            .where(condition)
            .orderBy(ORDER.createdAt.desc(), ORDER.id.desc())
            .offset(criteria.offset())
            .limit(criteria.size())
            .fetch();
    }

    // 주문 헤더 조회
    private Optional<OrderHeaderRow> findHeader(long orderId) {
        return Optional.ofNullable(selectHeaders().where(ORDER.id.eq(orderId)).fetchOne());
    }

    private JPAQuery<OrderHeaderRow> selectHeaders() {
        ConstructorExpression<OrderHeaderRow> projection = Projections.constructor(OrderHeaderRow.class,
            ORDER.id, ORDER.userId, ORDER.status, ORDER.totalAmount, RECORD.amount, RECORD.status, ORDER.createdAt);
        return queryFactory.select(projection)
            .from(ORDER)
            .leftJoin(ORDER.record, RECORD);
    }

    // 단건 주문의 품목 조회
    private List<OrderItemView> findItemsByOrderId(long orderId) {
        return selectItems()
            .where(ITEM.order.id.eq(orderId))
            .orderBy(ITEM.id.asc())
            .fetch()
            .stream()
            .map(OrderItemRow::toView)
            .toList();
    }

    // 여러 주문의 품목을 한번에 조회
    private Map<Long, List<OrderItemView>> findItemsByOrderIds(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderItemRow> rows = selectItems()
            .where(ITEM.order.id.in(orderIds))
            .orderBy(ITEM.order.id.asc(), ITEM.id.asc())
            .fetch();
        return rows.stream()
            .collect(Collectors.groupingBy(OrderItemRow::orderId,
                Collectors.mapping(OrderItemRow::toView, Collectors.toList())));
    }

    private JPAQuery<OrderItemRow> selectItems() {
        return queryFactory.select(Projections.constructor(OrderItemRow.class, ITEM.order.id, ITEM.productId,
                ITEM.productName, ITEM.unitPrice, ITEM.quantity, ITEM.amount))
            .from(ITEM);
    }

    private List<Long> orderIdsOf(List<OrderHeaderRow> headers) {
        return headers.stream().map(OrderHeaderRow::orderId).toList();
    }
}
