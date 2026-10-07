package com.loopers.domain.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface OrderRepository {
    /** 주문 품목을 함께 복원한다. */
    Optional<OrderModel> find(Long orderId);

    /**
     * 변경을 위해 Order 행만 비관적 쓰기 잠금으로 조회한다. 품목은 함께 복원하지 않으며,
     * 필요하면 같은 트랜잭션에서 별도로 복원한다. 잠금은 호출한 트랜잭션이 끝날 때까지 유지된다.
     */
    Optional<OrderModel> findForUpdate(Long orderId);

    OrderModel save(OrderModel order);

    /** Order 단위로 페이지를 나누고 각 주문의 품목을 함께 복원한다. */
    PageResult<OrderModel> findPageByUserId(Long userId, PageCommand page, ListSort sort);

    /** 관리자 조회: 소유자와 무관하게 Order 단위로 페이지를 나눈다. */
    PageResult<OrderModel> findPage(PageCommand page, ListSort sort);
}
