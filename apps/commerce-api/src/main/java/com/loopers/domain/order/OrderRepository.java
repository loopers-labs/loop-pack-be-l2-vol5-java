package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<OrderModel> findById(Long id);

    Optional<OrderModel> findByIdWithLock(Long id);

    List<OrderModel> findAllByUserId(Long userId);

    Page<OrderModel> findAll(Pageable pageable);

    List<OrderModel> findExpiredDrafts(ZonedDateTime now);

    List<OrderModel> findByStatus(OrderStatus status);

    OrderModel save(OrderModel order);

    void saveAll(List<OrderModel> orders);
}
