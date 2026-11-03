package com.loopers.domains.order.app.port;

import com.loopers.domains.order.domain.Order;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(long id);
    Optional<Order> findByIdForUpdate(long id);
    List<Order> findPage(Long userId, int page, int size);
}
