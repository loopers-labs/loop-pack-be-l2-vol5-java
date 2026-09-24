package com.loopers.domain.ordering.order;

import java.util.Optional;

// 주문 저장소 인터페이스
public interface OrderRepository {
    Order save(Order order);

    Optional<Order> findById(long orderId);

    // 비관적 쓰기 잠금으로 조회
    Optional<Order> findByIdForUpdate(long orderId);
}
