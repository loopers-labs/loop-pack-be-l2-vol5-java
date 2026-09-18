package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long orderId);

    Optional<Order> findByIdForUpdate(Long orderId);

    List<Order> findByUserId(Long userId);

    List<Order> findPage(Long userId, OrderStatus status, int offset, int limit);
}
