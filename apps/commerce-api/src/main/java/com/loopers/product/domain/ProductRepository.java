package com.loopers.product.domain;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(Long id);

    List<Product> findAllByBrandId(Long brandId);

    List<Product> findAllByBrandIdAndName(Long brandId, String name);

    List<Product> findAll(Long brandId, int page, int size);

    List<Product> findCustomerProducts(Long brandId, ProductSort sort, int page, int size);

    long countAll(Long brandId);

    long countCustomerProducts(Long brandId);
}
