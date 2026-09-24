package com.loopers.domain.mall.brand;

import java.util.Optional;

// 브랜드 저장소 인터페이스
public interface BrandRepository {
    Brand save(Brand brand);

    Optional<Brand> findById(long brandId);

    // 브랜드와 연결된 미삭제 상품 전체를 함께 조회 (삭제 전용)
    Optional<Brand> findForDeletion(long brandId);
}
