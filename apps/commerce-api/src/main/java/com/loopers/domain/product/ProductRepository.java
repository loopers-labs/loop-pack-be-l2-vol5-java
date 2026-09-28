package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> find(Long id);

    List<ProductModel> findByIds(Collection<Long> ids);

    /** FR-ADMIN-BRAND-05: 삭제되지 않은 소속 상품 유무 (재고 0 도 "있음"). */
    boolean existsActiveByBrandId(Long brandId);
}
