package com.loopers.application.ordering.query;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import java.util.Optional;

// 주문 조회 전용 DAO
public interface OrderQueryDao {
    PageResult<OrderView> findOrders(long userId, PageCriteria criteria);

    Optional<OrderView> findOrder(long orderId);

    PageResult<AdminOrderView> findAdminOrders(PageCriteria criteria);

    Optional<AdminOrderView> findAdminOrder(long orderId);
}
