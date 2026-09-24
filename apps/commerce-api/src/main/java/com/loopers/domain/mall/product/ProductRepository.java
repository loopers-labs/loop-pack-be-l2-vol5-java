package com.loopers.domain.mall.product;

import java.util.Optional;

// 상품 저장소 인터페이스
public interface ProductRepository {
    Product save(Product product);

    Optional<Product> findById(long productId);

    // 비관적 쓰기 잠금으로 조회
    Optional<Product> findByIdForUpdate(long productId);
}
