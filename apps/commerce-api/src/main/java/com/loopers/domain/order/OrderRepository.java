package com.loopers.domain.order;

import com.loopers.domain.common.PageCondition;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    Optional<Order> find(Long id);

    List<Order> findByUserId(Long userId, PageCondition page);

    long countByUserId(Long userId);

    List<Order> findAll(Long userId, PageCondition page);

    long countAll(Long userId);
}
