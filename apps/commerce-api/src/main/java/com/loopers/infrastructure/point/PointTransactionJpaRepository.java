package com.loopers.infrastructure.point;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface PointTransactionJpaRepository extends JpaRepository<PointTransactionEntity, Long> {

    List<PointTransactionEntity> findByUserIdOrderByIdAsc(Long userId);
}
