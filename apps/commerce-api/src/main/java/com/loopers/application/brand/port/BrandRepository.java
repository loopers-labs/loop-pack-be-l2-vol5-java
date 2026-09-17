package com.loopers.application.brand.port;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;

import java.util.Optional;

public interface BrandRepository {
    // 신규 저장 시 DB가 부여한 ID를 포함한 브랜드를 반환한다.
    Brand save(Brand brand);

    Optional<Brand> findById(BrandId id);
}
