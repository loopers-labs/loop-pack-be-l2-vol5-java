package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {
    private final BrandJpaRepository brandJpaRepository;

    @Override
    public Brand save(Brand brand) {
        return brandJpaRepository.save(brand);
    }

    @Override
    public Optional<Brand> findActive(Long brandId) {
        return brandJpaRepository.findByIdAndDeletedAtIsNull(brandId);
    }

    @Override
    public Page<Brand> findActive(Pageable pageable) {
        return brandJpaRepository.findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(pageable);
    }

    @Override
    public Optional<Brand> findActiveForUpdate(Long brandId) {
        return brandJpaRepository.findActiveForUpdate(brandId);
    }

    @Override
    public Optional<Brand> findActiveForShare(Long brandId) {
        return brandJpaRepository.findActiveForShare(brandId);
    }
}
