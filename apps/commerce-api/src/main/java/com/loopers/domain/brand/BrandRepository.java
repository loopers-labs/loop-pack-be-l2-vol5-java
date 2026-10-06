package com.loopers.domain.brand;

import java.util.Optional;
import java.util.List;
import java.time.ZonedDateTime;

public interface BrandRepository {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long brandId);

    Optional<Brand> findById(Long brandId);

    Optional<Brand> findActiveById(Long brandId);

    List<Brand> findAll();

    List<Brand> findAllActive();

    List<Brand> findAllDeleted();

    List<Brand> findAllByIds(List<Long> brandIds);

    int updateActiveName(Long brandId, String name, ZonedDateTime updatedAt);

    int softDeleteActiveById(Long brandId, ZonedDateTime deletedAt);

    Brand save(Brand brand);
}
