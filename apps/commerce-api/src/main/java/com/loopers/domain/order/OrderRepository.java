package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    /** 소유자까지 조건으로 건 조회. 타인의 주문은 없는 주문과 같다 (ORD-06, 설계 6.1). */
    Optional<Order> findByIdAndUserId(Long orderId, Long userId);

    Optional<Order> findById(Long orderId);

    /** 주문 시각 desc, 식별자 desc. userId · status 가 null 이면 그 조건으로 거르지 않는다. */
    Page<Order> findPage(Long userId, OrderStatus status, Pageable pageable);
}
