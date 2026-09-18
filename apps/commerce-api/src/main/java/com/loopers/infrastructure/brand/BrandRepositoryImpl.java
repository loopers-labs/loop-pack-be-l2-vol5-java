package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;

    public BrandRepositoryImpl(BrandJpaRepository brandJpaRepository) {
        this.brandJpaRepository = brandJpaRepository;
    }

    @Override
    public Brand save(Brand brand) {
        return brandJpaRepository.saveAndFlush(brand);
    }

    @Override
    public Optional<Brand> findById(long brandId) {
        return brandJpaRepository.findById(brandId);
    }

    @Override
    public Optional<Brand> lockById(long brandId) {
        return brandJpaRepository.lockById(brandId);
    }
}
