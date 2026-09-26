package com.loopers.domain.brand;

import java.util.Optional;
import java.util.List;

public interface BrandRepository {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long brandId);

    Optional<Brand> findById(Long brandId);

    Optional<Brand> findActiveById(Long brandId);

    List<Brand> findAll();

    List<Brand> findAllActive();

    List<Brand> findAllDeleted();

    List<Brand> findAllByIds(List<Long> brandIds);

    Brand save(Brand brand);
}
