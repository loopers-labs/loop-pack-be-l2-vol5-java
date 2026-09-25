package com.loopers.infrastructure.brand;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<BrandJpaEntity, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long brandId);

    Optional<BrandJpaEntity> findByName(String name);

    Optional<BrandJpaEntity> findByIdAndDeletedAtIsNull(Long brandId);

    List<BrandJpaEntity> findAllByDeletedAtIsNull();

    List<BrandJpaEntity> findAllByDeletedAtIsNotNull();
}
