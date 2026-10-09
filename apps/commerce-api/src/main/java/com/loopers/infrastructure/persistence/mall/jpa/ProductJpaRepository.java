package com.loopers.infrastructure.persistence.mall.jpa;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.loopers.infrastructure.persistence.mall.entity.ProductJpaEntity;

// 상품 Spring Data JPA 레포지토리
public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    // 비관적 쓰기 잠금으로 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id = :id")
    Optional<ProductJpaEntity> findByIdForUpdate(@Param("id") long id);

    // 브랜드의 미삭제 상품 전체를 한 번의 UPDATE로 삭제 처리
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductJpaEntity p set p.deleted = true, p.updatedAt = :now "
        + "where p.brandId = :brandId and p.deleted = false")
    int deleteAllActiveByBrandId(@Param("brandId") long brandId, @Param("now") Instant now);
}
