package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<OrderModel> findById(Long id);

    List<OrderModel> findAllByUserId(Long userId);

    OrderModel save(OrderModel order);
}
