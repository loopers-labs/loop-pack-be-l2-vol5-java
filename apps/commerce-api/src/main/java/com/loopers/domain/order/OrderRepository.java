package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface OrderRepository {
    OrderModel save(OrderModel order);

    Optional<OrderModel> findByIdAndUserId(Long id, Long userId);

    Optional<OrderModel> findByIdAndUserIdForUpdate(Long id, Long userId);

    // 소유자를 모르는 경로(callback의 결과 적용)에서 쓴다. 소유권은 호출 전에 이미 확인했다
    Optional<OrderModel> findByIdForUpdate(Long id);

    Optional<OrderModel> findById(Long id);

    Page<OrderModel> findAllByUserId(Long userId, Pageable pageable);

    Page<OrderModel> findAll(Pageable pageable);
}
