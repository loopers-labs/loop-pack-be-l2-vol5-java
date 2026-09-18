package com.loopers.domain.order;

import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    Optional<Order> findById(long orderId);

    Optional<Order> lockById(long orderId);

    PageResult<Order> findPage(Long userId, OrderStatus status, int page, int size);
}
