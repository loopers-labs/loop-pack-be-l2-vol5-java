package com.loopers.infrastructure.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderEventHoldJpaRepository extends JpaRepository<OrderEventHoldEntity, Long> {
}
