package com.loopers.domains.order.out;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutboxJpaEntity, Long> {
}
