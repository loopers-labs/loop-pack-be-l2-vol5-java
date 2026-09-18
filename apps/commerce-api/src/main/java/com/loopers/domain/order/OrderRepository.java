package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderRepository {
    Optional<Order> findById(long id);
    Order save(Order order);
    List<Order> findByUserId(long userId);
    Page<Order> findAll(Long userId, Pageable pageable);
}
