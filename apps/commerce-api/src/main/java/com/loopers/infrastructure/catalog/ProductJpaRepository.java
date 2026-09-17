package com.loopers.infrastructure.catalog;

import com.loopers.domain.catalog.ProductModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    boolean existsByBrandIdAndDeletedAtIsNull(Long brandId);
}
