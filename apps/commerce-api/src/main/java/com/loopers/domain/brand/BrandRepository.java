package com.loopers.domain.brand;

import java.util.List;
import java.util.Optional;

public interface BrandRepository {

    Brand save(Brand brand);

    Optional<Brand> findById(Long id);

    Optional<Brand> findByIdForShare(Long id);

    Optional<Brand> findByIdForUpdate(Long id);

    List<Brand> findPage(int offset, int limit);
}
