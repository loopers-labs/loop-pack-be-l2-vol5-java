package com.loopers.infrastructure.activity;

import org.springframework.data.jpa.repository.JpaRepository;

interface PurchaseActivityJpaRepository extends JpaRepository<PurchaseActivityEntity, Long> {
}
