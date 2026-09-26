package com.loopers.infrastructure.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {

    @EntityGraph(attributePaths = "items")
    @Override
    Optional<OrderJpaEntity> findById(Long orderId);

    @EntityGraph(attributePaths = "items")
    List<OrderJpaEntity> findAllByUserId(Long userId);

    @EntityGraph(attributePaths = "items")
    @Override
    List<OrderJpaEntity> findAll();
}
