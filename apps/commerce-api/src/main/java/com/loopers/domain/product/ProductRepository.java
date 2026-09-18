package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> findActiveById(Long id);

    Optional<ProductModel> findById(Long id);

    Page<ProductModel> findAll(Pageable pageable);

    Page<ProductModel> findAllActive(Pageable pageable);

    Page<ProductModel> findAllActiveByBrandId(Long brandId, Pageable pageable);

    List<ProductModel> findAllActiveByIds(List<Long> ids);

    boolean existsActiveByBrandId(Long brandId);
}
