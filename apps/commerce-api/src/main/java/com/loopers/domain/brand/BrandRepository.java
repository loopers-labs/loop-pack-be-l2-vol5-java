package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BrandRepository {

    BrandModel save(BrandModel brand);

    /**
     * BRD-03: 삭제되지 않은 브랜드만 찾는다.
     */
    Optional<BrandModel> findActiveById(Long id);

    Page<BrandModel> findActive(Pageable pageable);
}
