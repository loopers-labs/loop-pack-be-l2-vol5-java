package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Optional<ProductModel> findActiveById(Long id);

    Optional<ProductModel> findById(Long id);

    Page<ProductModel> findActiveProducts(Long brandId, ProductSortType sortType, Pageable pageable);

    List<ProductModel> findAllActiveByIds(List<Long> ids);

    Page<ProductModel> findAll(Pageable pageable);

    boolean existsActiveByBrandId(Long brandId);

    ProductModel save(ProductModel product);
}
