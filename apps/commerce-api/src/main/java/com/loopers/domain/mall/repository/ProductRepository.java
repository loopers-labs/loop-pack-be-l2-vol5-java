package com.loopers.domain.mall.repository;

import com.loopers.domain.mall.model.Product;
import java.util.Optional;

// 상품 저장소 인터페이스
public interface ProductRepository {
    Product save(Product product);

    Optional<Product> findById(long productId);

    // 비관적 쓰기 잠금으로 조회
    Optional<Product> findByIdForUpdate(long productId);

    // 브랜드의 미삭제 상품 전체를 한 번에 삭제 처리하고 삭제 건수를 반환
    int deleteAllByBrandId(long brandId);
}
