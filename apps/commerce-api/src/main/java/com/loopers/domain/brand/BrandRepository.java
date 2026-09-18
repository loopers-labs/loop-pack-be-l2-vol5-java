package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface BrandRepository {
    BrandModel save(BrandModel brand);

    Optional<BrandModel> findActiveById(Long id);

    Optional<BrandModel> findById(Long id);

    Page<BrandModel> findAll(Pageable pageable);

    List<BrandModel> findAllByIds(List<Long> ids);
}
