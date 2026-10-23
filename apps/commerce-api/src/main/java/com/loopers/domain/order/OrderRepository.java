package com.loopers.domain.order;

import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    Optional<Order> findById(long orderId);

    Optional<Order> lockById(long orderId);

    /**
     * 호출자가 상품 쓰기 잠금을 보유한 READ_COMMITTED 트랜잭션에서 HELD 수량만 비잠금 집계한다.
     * 주문 잠금을 추가로 얻지 않으며 예약·소비·반환도 같은 상품 행 잠금에 참여해야 한다.
     */
    long sumHeldQuantityByProductId(long productId);

    PageResult<Order> findPage(Long userId, OrderStatus status, int page, int size);
}
