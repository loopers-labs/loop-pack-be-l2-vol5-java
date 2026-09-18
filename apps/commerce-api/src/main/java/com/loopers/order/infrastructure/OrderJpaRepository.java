package com.loopers.order.infrastructure;

import com.loopers.order.domain.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrderJpaRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByBuyerId(Long buyerId, Pageable pageable);

    long countByBuyerId(Long buyerId);
}
