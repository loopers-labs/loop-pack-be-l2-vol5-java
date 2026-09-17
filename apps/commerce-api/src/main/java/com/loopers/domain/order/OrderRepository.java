package com.loopers.domain.order;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    OrderModel save(OrderModel order);

    Optional<OrderModel> find(Long id);

    /** FR-ORDER-03: 요청자의 주문 전부, 최신순. */
    PageResult<OrderModel> findPageByUserId(Long userId, PageQuery query);

    /** FR-ADMIN-ORDER-01 1단계: 주문이 있는 구매자 ID 를 오름차순으로 페이지 단위로 자른다 (DR-11). */
    PageResult<Long> findBuyerIdPage(PageQuery query);

    /** FR-ADMIN-ORDER-01 2단계: 해당 구매자들의 주문, 최신순. */
    List<OrderModel> findByUserIds(Collection<Long> userIds);
}
