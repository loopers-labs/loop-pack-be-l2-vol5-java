package com.loopers.infrastructure.activity;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderEventHoldJpaRepository extends JpaRepository<OrderEventHoldEntity, Long> {
}
