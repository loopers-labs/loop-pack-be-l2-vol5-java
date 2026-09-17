package com.loopers.domain.brand;

import com.loopers.domain.common.PageCondition;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandRepository {
    Optional<Brand> findActive(Long id);

    List<Brand> findAll(Collection<Long> ids);

    List<Brand> findActive(PageCondition page);

    long countActive();

    Brand save(Brand brand);
}
