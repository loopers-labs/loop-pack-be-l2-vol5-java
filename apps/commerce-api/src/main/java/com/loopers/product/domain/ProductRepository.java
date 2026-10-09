package com.loopers.product.domain;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(Long id);

    Optional<Product> findForOrder(Long id);

    Optional<Product> findForLike(Long id);

    Optional<Product> findForStock(Long id);

    Optional<Product> findForWrite(Long id);

    List<Product> findAllForBrandDelete(Long brandId);

    List<Product> findAllByBrandId(Long brandId);

    List<Product> findAllByBrandIdAndName(Long brandId, String name);

    List<Product> findAll(Long brandId, int page, int size);

    List<Product> findCustomerProducts(Long brandId, ProductSort sort, int page, int size);

    long countAll(Long brandId);

    long countCustomerProducts(Long brandId);
}
