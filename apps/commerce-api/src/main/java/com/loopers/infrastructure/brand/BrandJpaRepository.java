package com.loopers.infrastructure.brand;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface BrandJpaRepository extends JpaRepository<BrandEntity, Long> {

    Optional<BrandEntity> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from BrandEntity b where b.id = :id and b.deletedAt is null")
    Optional<BrandEntity> findAliveByIdForShare(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BrandEntity b where b.id = :id and b.deletedAt is null")
    Optional<BrandEntity> findAliveByIdForUpdate(Long id);
}
