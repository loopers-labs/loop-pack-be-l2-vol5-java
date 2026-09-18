package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<Brand, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Brand b where b.id = :brandId")
    Optional<Brand> lockById(long brandId);
}
