package com.loopers.domain.order;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.List;

public interface OrderRepository {
    Order save(Order order);
    int confirmIfDraft(Long orderId, Long userId, long paymentAmount, ZonedDateTime confirmedAt);
    Optional<Order> findById(Long orderId);
    List<Order> findAllByUserId(Long userId);
    List<Order> findAll();
}
