package com.loopers.domain.brand;

import java.util.Optional;

public interface BrandRepository {

    boolean existsByName(String name);

    Optional<Brand> findById(Long brandId);

    Brand save(Brand brand);
}
