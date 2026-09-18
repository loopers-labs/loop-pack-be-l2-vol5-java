package com.loopers.infrastructure.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrderItemJpaRepository extends JpaRepository<OrderItemEntity, Long> {

    List<OrderItemEntity> findByOrderIdOrderByIdAsc(Long orderId);

    List<OrderItemEntity> findByOrderIdInOrderByIdAsc(List<Long> orderIds);
}
