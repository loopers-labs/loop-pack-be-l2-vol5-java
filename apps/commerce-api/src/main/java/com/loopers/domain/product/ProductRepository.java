package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    /** 삭제되지 않은 상품만 조회한다. */
    Optional<ProductModel> findActive(Long productId);

    /** 삭제되지 않은 상품만 조회한다. 없는 식별자는 결과에서 빠진다. */
    List<ProductModel> findAllActiveByIds(Collection<Long> productIds);

    /** 삭제되지 않은 상품이 해당 브랜드에 하나라도 남아 있는지 확인한다. */
    boolean existsActiveByBrandId(Long brandId);

    ProductModel save(ProductModel product);
}
