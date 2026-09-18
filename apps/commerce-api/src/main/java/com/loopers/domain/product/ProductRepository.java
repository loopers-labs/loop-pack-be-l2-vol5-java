package com.loopers.domain.product;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepository {
    Optional<Product> findById(long id);
    Product save(Product product);
    Page<Product> findAll(Pageable pageable);
}
