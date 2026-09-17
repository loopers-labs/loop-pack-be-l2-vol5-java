package com.loopers.domain.order;

import java.util.Optional;
import java.util.List;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(Long orderId);
    List<Order> findAllByUserId(Long userId);
    List<Order> findAll();
}
