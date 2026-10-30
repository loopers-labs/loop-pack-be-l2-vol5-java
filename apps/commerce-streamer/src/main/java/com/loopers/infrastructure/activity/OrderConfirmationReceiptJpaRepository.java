package com.loopers.infrastructure.activity;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderConfirmationReceiptJpaRepository extends JpaRepository<OrderConfirmationReceiptEntity, Long> {
}
