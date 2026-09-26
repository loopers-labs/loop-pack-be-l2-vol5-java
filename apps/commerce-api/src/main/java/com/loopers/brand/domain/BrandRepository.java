package com.loopers.brand.domain;

import java.util.List;
import java.util.Optional;

public interface BrandRepository {

    Brand save(Brand brand);

    Optional<Brand> findById(Long id);

    List<Brand> findAllByName(String name);

    List<Brand> findAll(int page, int size);

    long countAll();
}
