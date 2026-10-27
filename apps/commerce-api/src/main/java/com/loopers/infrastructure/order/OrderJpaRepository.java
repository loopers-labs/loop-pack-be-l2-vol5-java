package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByUserId(Long userId);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);
}