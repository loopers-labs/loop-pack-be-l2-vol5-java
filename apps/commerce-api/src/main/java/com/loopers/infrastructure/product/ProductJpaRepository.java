package com.loopers.infrastructure.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

interface ProductJpaRepository extends JpaRepository<ProductEntity, Long> {

    boolean existsByBrandIdAndDeletedAtIsNull(Long brandId);

    Optional<ProductEntity> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductEntity p where p.id = :id and p.deletedAt is null")
    Optional<ProductEntity> findAliveByIdForUpdate(Long id);
}
