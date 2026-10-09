package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    OrderModel save(OrderModel order);
    Optional<OrderModel> find(Long id);
    Optional<OrderModel> findForUpdate(Long id);
    List<OrderModel> findByUserId(Long userId);
    List<OrderModel> findAll();
}
