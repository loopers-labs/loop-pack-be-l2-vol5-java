package com.loopers.order.domain;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long id);

    List<Order> findAllByBuyerId(Long buyerId, int page, int size);

    List<Order> findAll(Long buyerId, int page, int size);

    long countAllByBuyerId(Long buyerId);

    long countAll(Long buyerId);
}
