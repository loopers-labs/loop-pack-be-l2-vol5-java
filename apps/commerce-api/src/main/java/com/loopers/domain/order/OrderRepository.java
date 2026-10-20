package com.loopers.domain.order;

import java.util.Optional;

public interface OrderRepository {
    OrderModel save(OrderModel order);

    Optional<OrderModel> find(Long id);

    /** 결제 시작·결과 반영용: 비관적 쓰기 락(SELECT ... FOR UPDATE). 주문 행을 다른 락보다 먼저 잡는다 (주문 → 재고 → 상품). */
    Optional<OrderModel> findForUpdate(Long id);
}
