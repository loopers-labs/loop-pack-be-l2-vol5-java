package com.loopers.domain.brand;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BrandRepository {
    Optional<Brand> findById(long id);
    Brand save(Brand brand);
    Page<Brand> findAll(Pageable pageable);
}
