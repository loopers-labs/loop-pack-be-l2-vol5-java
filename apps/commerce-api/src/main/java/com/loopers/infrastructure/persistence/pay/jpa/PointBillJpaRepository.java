package com.loopers.infrastructure.persistence.pay.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import com.loopers.infrastructure.persistence.pay.entity.PointBillJpaEntity;

// 포인트 기록 Spring Data JPA 레포지토리
public interface PointBillJpaRepository extends JpaRepository<PointBillJpaEntity, Long> {}
