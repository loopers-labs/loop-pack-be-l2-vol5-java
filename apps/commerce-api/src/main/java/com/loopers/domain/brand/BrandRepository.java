package com.loopers.domain.brand;

public interface BrandRepository {

    boolean existsByName(String name);

    Brand save(Brand brand);
}
