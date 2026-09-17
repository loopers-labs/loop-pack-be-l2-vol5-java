package com.loopers.application.order.port;

import com.loopers.domain.order.Order;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(long id);
    Optional<Order> findByIdForUpdate(long id);
    List<Order> findPage(Long userId, int page, int size);
}
