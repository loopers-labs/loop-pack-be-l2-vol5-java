package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<BrandModel, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BrandModel b where b.id = :id")
    Optional<BrandModel> findByIdForUpdate(@Param("id") Long id);
}
