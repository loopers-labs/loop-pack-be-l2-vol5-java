package com.loopers.domain.product;

import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(Long productId);

    Product save(Product product);
}
