package com.loopers.infrastructure.order;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderEventOutboxJpaRepository extends JpaRepository<OrderEventOutboxEntity, Long> {
}
