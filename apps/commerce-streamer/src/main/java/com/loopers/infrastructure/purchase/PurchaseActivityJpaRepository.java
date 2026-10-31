package com.loopers.infrastructure.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

interface PurchaseActivityJpaRepository extends JpaRepository<PurchaseActivityEntity, Long> {
}
