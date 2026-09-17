package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface OrderRepository {

    OrderModel save(OrderModel order);

    Optional<OrderModel> findById(Long orderId);

    /**
     * userId가 null이면 모든 구매자의 주문 (관리자 조회, ORD-06).
     */
    Page<OrderModel> findAll(Long userId, Pageable pageable);
}
