package com.loopers.domain.product;

import java.util.Optional;
import java.util.List;

public interface ProductRepository {

    Optional<Product> findById(Long productId);

    List<Product> findAll();

    List<Product> findAllActive();

    List<Product> findAllDeleted();

    boolean existsActiveByBrandId(Long brandId);

    Product save(Product product);
}
