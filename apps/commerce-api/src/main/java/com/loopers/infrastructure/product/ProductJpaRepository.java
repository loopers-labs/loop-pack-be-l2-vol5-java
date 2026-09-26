package com.loopers.infrastructure.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    boolean existsByBrand_IdAndDeletedAtIsNull(Long brandId);

    List<ProductJpaEntity> findAllByDeletedAtIsNull();

    List<ProductJpaEntity> findAllByDeletedAtIsNotNull();
}
