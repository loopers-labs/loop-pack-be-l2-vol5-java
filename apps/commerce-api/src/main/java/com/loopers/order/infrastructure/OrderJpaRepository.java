package com.loopers.order.infrastructure;

import com.loopers.order.domain.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface OrderJpaRepository extends JpaRepository<Order, Long> {

    Optional<Order> findForConfirmById(Long id);

    List<Order> findAllByBuyerId(Long buyerId, Pageable pageable);

    long countByBuyerId(Long buyerId);
}
