package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;

    @Override
    public boolean existsByName(String name) {
        return brandJpaRepository.existsByName(name);
    }

    @Override
    public Brand save(Brand brand) {
        return brandJpaRepository.save(brand);
    }
}
