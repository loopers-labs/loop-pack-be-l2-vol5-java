package com.loopers.domain.product;

import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(Long id);

    Optional<Product> findByIdForUpdate(Long id);

    boolean existsByBrandId(Long brandId);
}
