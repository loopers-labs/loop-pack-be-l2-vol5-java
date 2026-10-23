package com.loopers.domain.order;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.List;

public interface OrderRepository {
    Order save(Order order);
    int requestPgPaymentIfDraft(Long orderId, Long userId, ZonedDateTime requestedAt);
    int completePgPaymentIfPending(Long orderId, long paymentAmount, ZonedDateTime completedAt);
    int failPgPaymentIfPending(Long orderId, ZonedDateTime failedAt);
    Optional<Order> findById(Long orderId);
    List<Order> findAllByUserId(Long userId);
    List<Order> findAll();
}
