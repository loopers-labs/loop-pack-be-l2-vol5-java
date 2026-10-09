package com.loopers.domain.mall.repository;

import com.loopers.domain.mall.model.Brand;
import java.util.Optional;

// 브랜드 저장소 인터페이스
public interface BrandRepository {
    Brand save(Brand brand);

    Optional<Brand> findById(long brandId);

    // 비관적 쓰기 잠금으로 조회
    Optional<Brand> findByIdForUpdate(long brandId);

    // 비관적 공유 잠금으로 조회
    Optional<Brand> findByIdForShare(long brandId);
}
