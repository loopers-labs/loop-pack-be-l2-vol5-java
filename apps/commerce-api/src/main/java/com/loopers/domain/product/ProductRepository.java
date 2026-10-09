package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> find(Long id);

    Optional<ProductModel> findForUpdate(Long id);

    List<ProductModel> findAllActive();

    List<ProductModel> findActiveByIds(Collection<Long> ids);

    List<ProductModel> findActiveByBrandIdForUpdate(Long brandId);
}
