package com.loopers.application.brand.port;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;

import java.util.Optional;

public interface BrandRepository {
    // 신규 저장 시 DB가 부여한 ID를 포함한 브랜드를 반환한다.
    java.util.List<Brand> findPage(int page, int size);
    java.util.List<Brand> findAllByIds(java.util.Collection<BrandId> ids);
    Optional<Brand> findByIdForUpdate(BrandId id);
    Brand save(Brand brand);

    Optional<Brand> findById(BrandId id);
}
