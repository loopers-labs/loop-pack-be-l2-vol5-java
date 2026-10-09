package com.loopers.infrastructure.persistence.mall.jpa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.loopers.infrastructure.persistence.mall.entity.BrandJpaEntity;

// 브랜드 Spring Data JPA 레포지토리
public interface BrandJpaRepository extends JpaRepository<BrandJpaEntity, Long> {
    // 비관적 쓰기 잠금으로 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BrandJpaEntity b where b.id = :id")
    Optional<BrandJpaEntity> findByIdForUpdate(@Param("id") long id);

    // 비관적 공유 잠금으로 조회
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from BrandJpaEntity b where b.id = :id")
    Optional<BrandJpaEntity> findByIdForShare(@Param("id") long id);
}
