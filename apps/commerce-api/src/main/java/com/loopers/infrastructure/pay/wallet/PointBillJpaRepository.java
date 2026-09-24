package com.loopers.infrastructure.pay.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

// 포인트 기록 Spring Data JPA 레포지토리
public interface PointBillJpaRepository extends JpaRepository<PointBillJpaEntity, Long> {}
