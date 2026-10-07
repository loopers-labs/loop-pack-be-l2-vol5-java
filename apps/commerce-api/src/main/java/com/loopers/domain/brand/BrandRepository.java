package com.loopers.domain.brand;

import java.util.List;
import java.util.Optional;

public interface BrandRepository {
    Optional<BrandModel> findActiveById(Long id);

    Optional<BrandModel> findById(Long id);

    List<BrandModel> findAll();

    List<BrandModel> findAllByIds(List<Long> ids);

    BrandModel save(BrandModel brand);
}
