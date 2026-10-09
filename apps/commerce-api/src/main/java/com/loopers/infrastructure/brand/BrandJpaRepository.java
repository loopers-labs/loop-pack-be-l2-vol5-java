package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<Brand, Long> {
    Optional<Brand> findByIdAndDeletedAtIsNull(Long id);

    Page<Brand> findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Brand b where b.id = :id and b.deletedAt is null")
    Optional<Brand> findActiveForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from Brand b where b.id = :id and b.deletedAt is null")
    Optional<Brand> findActiveForShare(@Param("id") Long id);
}
