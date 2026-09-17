package com.loopers.domain.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface OrderRepository {
    /** 주문 품목을 함께 복원한다. */
    Optional<OrderModel> find(Long orderId);

    OrderModel save(OrderModel order);

    /** Order 단위로 페이지를 나누고 각 주문의 품목을 함께 복원한다. */
    PageResult<OrderModel> findPageByUserId(Long userId, PageCommand page, ListSort sort);

    /** 관리자 조회: 소유자와 무관하게 Order 단위로 페이지를 나눈다. */
    PageResult<OrderModel> findPage(PageCommand page, ListSort sort);
}
