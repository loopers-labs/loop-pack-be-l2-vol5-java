package com.loopers.domain.product;

import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(long productId);

    Optional<Product> lockById(long productId);

    Optional<Long> findBrandId(long productId);

    /**
     * 해당 브랜드에 속한 미삭제 상품이 하나라도 있는지 조회한다.
     * 재고가 0인 상품도 포함하고, 삭제된 상품은 제외한다.
     */
    boolean existsNonDeletedByBrandId(long brandId);
}
