package com.loopers.order.application.port.out;

import com.loopers.order.domain.OrderModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface OrderPort {

    OrderModel save(OrderModel order);

    Optional<OrderModel> findById(Long orderId);

    /**
     * userId가 null이면 모든 구매자의 주문 (관리자 조회, ORD-06).
     */
    Page<OrderModel> findAll(Long userId, Pageable pageable);
}
