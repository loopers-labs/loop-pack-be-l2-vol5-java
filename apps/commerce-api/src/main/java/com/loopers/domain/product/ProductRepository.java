package com.loopers.domain.product;

import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(Long productId);

    boolean existsActiveByBrandId(Long brandId);

    Product save(Product product);
}
